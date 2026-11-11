package cn.demo.common.workflow;

import cn.demo.common.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 协调决议和订单同库持久化；任何调用参与者的操作都在这里的数据库事务之外执行。
 */
public class WorkflowStore {
    public record Flow(String id, String protocol, Purchase purchase, String status, int step,
                       boolean manualCommit, int attempts, boolean blocked, boolean expired,
                       String lastError, LocalDateTime nextRetryAt, LocalDateTime expiresAt) {
    }

    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "CANCELLED", "COMPENSATED");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DataSource locks;
    private final ObjectMapper json;
    private final BusinessRepository business;
    private static final String SELECT = "SELECT *,expires_at<CURRENT_TIMESTAMP(6) AS expired FROM demo_workflows ";

    public WorkflowStore(JdbcTemplate jdbc, PlatformTransactionManager tm, DataSource locks,
                         ObjectMapper json, BusinessRepository business) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.locks = locks;
        this.json = json;
        this.business = business;
    }

    private String encode(Purchase p) {
        try {
            return json.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("协调请求编码失败", e);
        }
    }

    private Purchase decode(String value) {
        try {
            return json.readValue(value, Purchase.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("协调日志内容损坏", e);
        }
    }

    private final RowMapper<Flow> mapper = (rs, row) -> new Flow(rs.getString("id"), rs.getString("protocol"),
            decode(rs.getString("payload")), rs.getString("status"), rs.getInt("step"),
            rs.getBoolean("manual_commit"), rs.getInt("attempts"), rs.getBoolean("blocked"),
            rs.getBoolean("expired"), rs.getString("last_error"),
            rs.getTimestamp("next_retry_at").toLocalDateTime(), rs.getTimestamp("expires_at").toLocalDateTime());

    public Flow create(String protocol, Purchase p, boolean manual, int ttlSeconds) {
        return tx.execute(s -> {
            // Upsert 只锁定既有行，绝不覆盖旧 payload、决议或截止时间；并发重试也串行检查。
            jdbc.update("INSERT INTO demo_workflows(id,protocol,payload,status,manual_commit,expires_at) "
                            + "VALUES (?,?,?,?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))) "
                            + "ON DUPLICATE KEY UPDATE id=id",
                    p.orderId(), protocol, encode(p), "TCC".equals(protocol) ? "PREPARING" : "RUNNING", manual, ttlSeconds);
            Flow flow = jdbc.query(SELECT + "WHERE id=? FOR UPDATE", mapper, p.orderId()).get(0);
            if (!protocol.equals(flow.protocol()) || !p.sameBusiness(flow.purchase()))
                throw new BusinessException("同一业务号的协议或业务内容不一致");
            var orders = jdbc.queryForList("SELECT * FROM demo_orders WHERE order_id=?", p.orderId());
            if (orders.isEmpty()) business.insertOrder(protocol, p, "PENDING");
            else {
                var o = orders.get(0);
                if (!protocol.equals(o.get("scenario")) || !p.userId().equals(o.get("user_id"))
                        || !p.productId().equals(o.get("product_id"))
                        || ((Number) o.get("quantity")).intValue() != p.quantity()
                        || p.amount().compareTo((java.math.BigDecimal) o.get("amount")) != 0)
                    throw new BusinessException("业务号已由其他订单占用");
            }
            return flow;
        });
    }

    public Flow require(String id, String protocol) {
        var found = jdbc.query(SELECT + "WHERE id=? AND protocol=?", mapper, id, protocol);
        if (found.isEmpty()) throw new BusinessException("协调事务不存在");
        return found.get(0);
    }

    public List<Flow> list(String protocol) {
        return jdbc.query(SELECT + "WHERE protocol=? ORDER BY created_at DESC LIMIT 100", mapper, protocol);
    }

    public List<String> due(String protocol) {
        return jdbc.queryForList("SELECT id FROM demo_workflows WHERE protocol=? AND blocked=FALSE "
                + "AND status NOT IN ('SUCCEEDED','CANCELLED','COMPENSATED') "
                + "AND next_retry_at<=CURRENT_TIMESTAMP(6) "
                + "AND (status<>'PREPARED' OR expires_at<CURRENT_TIMESTAMP(6)) "
                + "ORDER BY created_at LIMIT 20", String.class, protocol);
    }

    public boolean advance(Flow f, int next) {
        String expected = "TCC".equals(f.protocol()) ? "PREPARING" : "RUNNING";
        if (!expected.equals(f.status()) || next != f.step() + 1 || next > 2) return false;
        return jdbc.update("UPDATE demo_workflows SET step=?,updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE id=? AND protocol=? AND status=? AND step=?",
                next, f.id(), f.protocol(), expected, f.step()) == 1;
    }

    public boolean transition(Flow f, String next, String error) {
        boolean legal = "TCC".equals(f.protocol())
                ? ("PREPARING".equals(f.status()) && Set.of("PREPARED", "CONFIRMING", "CANCELLING").contains(next))
                || ("PREPARED".equals(f.status()) && Set.of("CONFIRMING", "CANCELLING").contains(next))
                : "RUNNING".equals(f.status()) && "COMPENSATING".equals(next);
        if (!legal) return false;
        boolean reservationDecision = Set.of("PREPARED", "CONFIRMING").contains(next);
        if (reservationDecision && f.step() != 2) return false;
        // 决议是状态机的不可逆边，且以数据库实际 UPDATE 时刻判定预留截止时间。
        String deadline = reservationDecision ? " AND expires_at>=CURRENT_TIMESTAMP(6)" : "";
        return jdbc.update("UPDATE demo_workflows SET status=?,last_error=?,next_retry_at=CURRENT_TIMESTAMP(6),"
                        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND protocol=? AND status=? AND step=?" + deadline,
                next, shortMessage(error), f.id(), f.protocol(), f.status(), f.step()) == 1;
    }

    public void complete(Flow f, String terminal) {
        boolean legal = "TCC".equals(f.protocol())
                ? ("CONFIRMING".equals(f.status()) && "SUCCEEDED".equals(terminal) && f.step() == 2)
                || ("CANCELLING".equals(f.status()) && "CANCELLED".equals(terminal))
                : ("RUNNING".equals(f.status()) && "SUCCEEDED".equals(terminal) && f.step() == 2)
                || ("COMPENSATING".equals(f.status()) && "COMPENSATED".equals(terminal));
        if (!legal) return;
        tx.executeWithoutResult(s -> {
            int n = jdbc.update("UPDATE demo_workflows SET status=?,blocked=FALSE,updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE id=? AND protocol=? AND status=?", terminal, f.id(), f.protocol(), f.status());
            if (n == 0) return;
            n = jdbc.update("UPDATE demo_orders SET status=? WHERE order_id=? AND scenario=? AND status='PENDING'",
                    "SUCCEEDED".equals(terminal) ? "PAID" : "CANCELLED", f.id(), f.protocol());
            if (n != 1) throw new BusinessException("协调日志和订单状态不匹配，需要检查");
        });
    }

    public void defer(Flow f, Exception error, int maxAttempts) {
        int attempts = f.attempts() + 1;
        long seconds = new RetryPolicy(maxAttempts, 1, 60).delaySeconds(attempts);
        jdbc.update("UPDATE demo_workflows SET attempts=?,blocked=?,last_error=?,"
                        + "next_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE id=? AND protocol=? AND status=? AND attempts=?",
                attempts, attempts >= maxAttempts, shortMessage(error.toString()), seconds, f.id(), f.protocol(), f.status(), f.attempts());
    }

    public void reset(Flow f) {
        jdbc.update("UPDATE demo_workflows SET attempts=0,blocked=FALSE,next_retry_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND protocol=? AND status=?", f.id(), f.protocol(), f.status());
    }

    private String shortMessage(String value) {
        return value == null ? null : value.substring(0, Math.min(1000, value.length()));
    }

    /**
     * 会话命名锁仅用于协调，不持有跨库事务/业务行锁；状态 CAS 与参与者幂等仍是最后防线。
     */
    public boolean withLock(String id, Runnable action) {
        // 数据库可能按不区分大小写的 collation 比较 ID；别让同一行因路径大小写取得两把锁。
        var ids = jdbc.queryForList("SELECT id FROM demo_workflows WHERE id=?", String.class, id);
        String canonical = ids.isEmpty() ? id : ids.get(0);
        String name;
        try {
            name = "dtx:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8))).substring(0, 48);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (Connection c = locks.getConnection()) {
            boolean acquired = false;
            try {
                try (var ps = c.prepareStatement("SELECT GET_LOCK(?,0)")) {
                    ps.setString(1, name);
                    ps.setQueryTimeout(3);
                    try (var rs = ps.executeQuery()) {
                        acquired = rs.next() && rs.getInt(1) == 1;
                    }
                }
                if (!acquired) return false;
                action.run();
                return true;
            } finally {
                if (acquired) try (var ps = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    ps.setString(1, name);
                    ps.execute();
                } catch (SQLException ignored) { /* 断开连接时 MySQL 自行释放会话锁。 */ }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("协调锁连接失败", e);
        }
    }
}

package cn.demo.common.workflow;

import cn.demo.common.BusinessException;
import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import cn.demo.common.RetryPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 将协调决议与订单同库持久化，所有参与者调用均在本类数据库事务之外执行。
 *
 * @author liusw144
 */
public class WorkflowStore {
    /**
     * 表示协调事务的持久化状态与数据库时钟判定的超时结果。
     *
     * @author liusw144
     */
    public record Flow(String id, String protocol, Purchase purchase, String status, int step, boolean manualCommit,
            int attempts, boolean blocked, boolean expired, String lastError, LocalDateTime nextRetryAt,
            LocalDateTime expiresAt) {
    }

    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "CANCELLED", "COMPENSATED");
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowStore.class);
    private static final String WORKFLOW_SELECT_SQL = "SELECT *,expires_at<CURRENT_TIMESTAMP(6) AS expired "
            + "FROM demo_workflows ";
    private static final String ORDER_SCENARIO_COLUMN = "scenario";
    private static final String ORDER_USER_ID_COLUMN = "user_id";
    private static final int MAX_FORWARD_STEPS = 2;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private static final int LOCK_HASH_LENGTH = 48;
    private static final int LOCK_QUERY_TIMEOUT_SECONDS = 3;
    private static final long BASE_RETRY_SECONDS = 1L;
    private static final long MAX_RETRY_SECONDS = 60L;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final DataSource lockDataSource;
    private final ObjectMapper objectMapper;
    private final BusinessRepository businessRepository;
    private final RowMapper<Flow> flowMapper = (resultSet, rowNumber) -> new Flow(resultSet.getString("id"),
            resultSet.getString("protocol"), decode(resultSet.getString("payload")), resultSet.getString("status"),
            resultSet.getInt("step"), resultSet.getBoolean("manual_commit"), resultSet.getInt("attempts"),
            resultSet.getBoolean("blocked"), resultSet.getBoolean("expired"), resultSet.getString("last_error"),
            resultSet.getTimestamp("next_retry_at").toLocalDateTime(),
            resultSet.getTimestamp("expires_at").toLocalDateTime());

    public WorkflowStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            DataSource lockDataSource, ObjectMapper objectMapper, BusinessRepository businessRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.lockDataSource = lockDataSource;
        this.objectMapper = objectMapper;
        this.businessRepository = businessRepository;
    }

    private String encode(Purchase purchase) {
        try {
            return objectMapper.writeValueAsString(purchase);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("协调请求编码失败", exception);
        }
    }

    private Purchase decode(String value) {
        try {
            return objectMapper.readValue(value, Purchase.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("协调日志内容损坏", exception);
        }
    }

    /** 幂等创建协调日志与订单，并拒绝复用业务号时改变协议或业务内容。 */
    public Flow create(String protocol, Purchase purchase, boolean manual, int ttlSeconds) {
        return transactionTemplate.execute(transactionStatus -> {
            // Upsert 只锁定既有行，绝不覆盖旧 payload、决议或截止时间；并发重试也串行检查。
            jdbcTemplate.update(
                    "INSERT INTO demo_workflows(id,protocol,payload,status,manual_commit,expires_at) "
                            + "VALUES (?,?,?,?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))) "
                            + "ON DUPLICATE KEY UPDATE id=id",
                    purchase.orderId(), protocol, encode(purchase), "TCC".equals(protocol) ? "PREPARING" : "RUNNING",
                    manual, ttlSeconds);
            Flow flow = jdbcTemplate
                    .query(WORKFLOW_SELECT_SQL + "WHERE id=? FOR UPDATE", flowMapper, purchase.orderId()).get(0);
            if (!protocol.equals(flow.protocol()) || !purchase.sameBusiness(flow.purchase())) {
                throw new BusinessException("同一业务号的协议或业务内容不一致");
            }
            List<Map<String, Object>> orderRows = jdbcTemplate
                    .queryForList("SELECT * FROM demo_orders WHERE order_id=?", purchase.orderId());
            if (orderRows.isEmpty()) {
                businessRepository.insertOrder(protocol, purchase, "PENDING");
            } else {
                Map<String, Object> orderRow = orderRows.get(0);
                if (!protocol.equals(orderRow.get(ORDER_SCENARIO_COLUMN))
                        || !purchase.userId().equals(orderRow.get(ORDER_USER_ID_COLUMN))
                        || !purchase.productId().equals(orderRow.get("product_id"))
                        || ((Number) orderRow.get("quantity")).intValue() != purchase.quantity()
                        || purchase.amount().compareTo((BigDecimal) orderRow.get("amount")) != 0) {
                    throw new BusinessException("业务号已由其他订单占用");
                }
            }
            return flow;
        });
    }

    /** 查询指定协议的协调事务，不存在时抛出业务异常。 */
    public Flow require(String id, String protocol) {
        List<Flow> foundFlows = jdbcTemplate.query(WORKFLOW_SELECT_SQL + "WHERE id=? AND protocol=?", flowMapper, id,
                protocol);
        if (foundFlows.isEmpty()) {
            throw new BusinessException("协调事务不存在");
        }
        return foundFlows.get(0);
    }

    /** 查询指定协议最近创建的协调事务。 */
    public List<Flow> list(String protocol) {
        return jdbcTemplate.query(WORKFLOW_SELECT_SQL + "WHERE protocol=? ORDER BY created_at DESC LIMIT 100",
                flowMapper, protocol);
    }

    /** 查询允许恢复且已到期的协调事务编号。 */
    public List<String> due(String protocol) {
        return jdbcTemplate.queryForList("SELECT id FROM demo_workflows WHERE protocol=? AND blocked=FALSE "
                + "AND status NOT IN ('SUCCEEDED','CANCELLED','COMPENSATED') "
                + "AND next_retry_at<=CURRENT_TIMESTAMP(6) "
                + "AND (status<>'PREPARED' OR expires_at<CURRENT_TIMESTAMP(6)) " + "ORDER BY created_at LIMIT 20",
                String.class, protocol);
    }

    /** 通过状态与步骤比较更新正向进度，拒绝跳步或重复推进。 */
    public boolean advance(Flow flow, int nextStep) {
        String expectedStatus = "TCC".equals(flow.protocol()) ? "PREPARING" : "RUNNING";
        if (!expectedStatus.equals(flow.status()) || nextStep != flow.step() + 1 || nextStep > MAX_FORWARD_STEPS) {
            return false;
        }
        return jdbcTemplate.update(
                "UPDATE demo_workflows SET step=?,updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE id=? AND protocol=? AND status=? AND step=?",
                nextStep, flow.id(), flow.protocol(), expectedStatus, flow.step()) == 1;
    }

    /** 持久化合法的阶段决议，资源预留决议受数据库当前时间约束。 */
    public boolean transition(Flow flow, String nextStatus, String error) {
        boolean legalTransition = "TCC".equals(flow.protocol())
                ? ("PREPARING".equals(flow.status())
                        && Set.of("PREPARED", "CONFIRMING", "CANCELLING").contains(nextStatus))
                        || ("PREPARED".equals(flow.status()) && Set.of("CONFIRMING", "CANCELLING").contains(nextStatus))
                : "RUNNING".equals(flow.status()) && "COMPENSATING".equals(nextStatus);
        if (!legalTransition) {
            return false;
        }
        boolean reservationDecision = Set.of("PREPARED", "CONFIRMING").contains(nextStatus);
        if (reservationDecision && flow.step() != MAX_FORWARD_STEPS) {
            return false;
        }
        // 决议是状态机的不可逆边，且以数据库实际 UPDATE 时刻判定预留截止时间。
        String deadlineCondition = reservationDecision ? " AND expires_at>=CURRENT_TIMESTAMP(6)" : "";
        return jdbcTemplate.update(
                "UPDATE demo_workflows SET status=?,last_error=?,next_retry_at=CURRENT_TIMESTAMP(6),"
                        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND protocol=? AND status=? AND step=?"
                        + deadlineCondition,
                nextStatus, shortMessage(error), flow.id(), flow.protocol(), flow.status(), flow.step()) == 1;
    }

    /** 在同一事务中将协调日志与订单推进至匹配的终态。 */
    public void complete(Flow flow, String terminal) {
        boolean legalTransition = "TCC".equals(flow.protocol())
                ? ("CONFIRMING".equals(flow.status()) && "SUCCEEDED".equals(terminal)
                        && flow.step() == MAX_FORWARD_STEPS)
                        || ("CANCELLING".equals(flow.status()) && "CANCELLED".equals(terminal))
                : ("RUNNING".equals(flow.status()) && "SUCCEEDED".equals(terminal) && flow.step() == MAX_FORWARD_STEPS)
                        || ("COMPENSATING".equals(flow.status()) && "COMPENSATED".equals(terminal));
        if (!legalTransition) {
            return;
        }
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            int affectedRows = jdbcTemplate.update(
                    "UPDATE demo_workflows SET status=?,blocked=FALSE,updated_at=CURRENT_TIMESTAMP(6) "
                            + "WHERE id=? AND protocol=? AND status=?",
                    terminal, flow.id(), flow.protocol(), flow.status());
            if (affectedRows == 0) {
                return;
            }
            affectedRows = jdbcTemplate.update(
                    "UPDATE demo_orders SET status=? WHERE order_id=? AND scenario=? AND status='PENDING'",
                    "SUCCEEDED".equals(terminal) ? "PAID" : "CANCELLED", flow.id(), flow.protocol());
            if (affectedRows != 1) {
                throw new BusinessException("协调日志和订单状态不匹配，需要检查");
            }
        });
    }

    /** 保存二阶段异常并安排退避恢复，耗尽次数后阻塞自动恢复。 */
    public void defer(Flow flow, Exception exception, int maxAttempts) {
        int attempts = flow.attempts() + 1;
        long delaySeconds = new RetryPolicy(maxAttempts, BASE_RETRY_SECONDS, MAX_RETRY_SECONDS).delaySeconds(attempts);
        jdbcTemplate.update(
                "UPDATE demo_workflows SET attempts=?,blocked=?,last_error=?,"
                        + "next_retry_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE id=? AND protocol=? AND status=? AND attempts=?",
                attempts, attempts >= maxAttempts, shortMessage(exception.toString()), delaySeconds, flow.id(),
                flow.protocol(), flow.status(), flow.attempts());
    }

    /** 保留原状态和决议，仅清除恢复次数与阻塞标记。 */
    public void reset(Flow flow) {
        jdbcTemplate.update("UPDATE demo_workflows SET attempts=0,blocked=FALSE,next_retry_at=CURRENT_TIMESTAMP(6) "
                + "WHERE id=? AND protocol=? AND status=?", flow.id(), flow.protocol(), flow.status());
    }

    private String shortMessage(String value) {
        return value == null ? null : value.substring(0, Math.min(MAX_ERROR_MESSAGE_LENGTH, value.length()));
    }

    /**
     * 会话命名锁仅用于协调，不持有跨库事务或业务行锁；状态 CAS 与参与者幂等仍是最后防线。
     */
    public boolean withLock(String id, Runnable action) {
        // 数据库可能按不区分大小写的 collation 比较 ID；别让同一行因路径大小写取得两把锁。
        List<String> storedIds = jdbcTemplate.queryForList("SELECT id FROM demo_workflows WHERE id=?", String.class,
                id);
        String canonicalId = storedIds.isEmpty() ? id : storedIds.get(0);
        String lockName;
        try {
            lockName = "dtx:" + HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(canonicalId.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, LOCK_HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法生成协调锁名称，缺少 SHA-256 算法", exception);
        }
        try (Connection connection = lockDataSource.getConnection()) {
            boolean acquired = false;
            try {
                try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
                    preparedStatement.setString(1, lockName);
                    preparedStatement.setQueryTimeout(LOCK_QUERY_TIMEOUT_SECONDS);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {
                        acquired = resultSet.next() && resultSet.getInt(1) == 1;
                    }
                }
                if (!acquired) {
                    return false;
                }
                action.run();
                return true;
            } finally {
                if (acquired) {
                    try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                        preparedStatement.setString(1, lockName);
                        preparedStatement.execute();
                    } catch (SQLException exception) {
                        // 断开连接时 MySQL 自行释放会话锁，释放失败不覆盖原业务结果。
                        LOGGER.warn("释放协调锁 {} 失败，连接关闭后由数据库释放", lockName, exception);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("协调锁连接失败", exception);
        }
    }
}

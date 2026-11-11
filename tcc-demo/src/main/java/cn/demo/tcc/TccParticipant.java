package cn.demo.tcc;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import cn.demo.common.workflow.ProtocolParticipant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Try 冻结、Confirm 消耗、Cancel 释放；分支日志与业务资源始终同库提交。
 */
public class TccParticipant implements ProtocolParticipant {
    private enum Action {TRY, CONFIRM, CANCEL}

    private enum State {NEW, RESERVED, CONFIRMED, CANCELLED}

    private final JdbcTemplate mysql, pg;
    private final TransactionTemplate mysqlTx, pgTx;
    private final ObjectMapper json;

    public TccParticipant(JdbcTemplate mysql, JdbcTemplate pg, PlatformTransactionManager mysqlTx,
                          PlatformTransactionManager pgTx, ObjectMapper json) {
        this.mysql = mysql;
        this.pg = pg;
        this.mysqlTx = localTransaction(mysqlTx);
        this.pgTx = localTransaction(pgTx);
        this.json = json;
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager manager) {
        var tx = new TransactionTemplate(manager);
        // 分支必须有自己的提交边界，不能误加入协调器或调用方的本地事务。
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    @Override
    public String protocol() {
        return "TCC";
    }

    @Override
    public void forward(Resource resource, Purchase purchase) {
        execute(resource, purchase, Action.TRY);
    }

    @Override
    public void confirm(Resource resource, Purchase purchase) {
        execute(resource, purchase, Action.CONFIRM);
    }

    @Override
    public void reverse(Resource resource, Purchase purchase) {
        execute(resource, purchase, Action.CANCEL);
    }

    private void execute(Resource resource, Purchase p, Action action) {
        JdbcTemplate jdbc = resource == Resource.STOCK ? mysql : pg;
        TransactionTemplate tx = resource == Resource.STOCK ? mysqlTx : pgTx;
        String payload = encode(p);
        tx.executeWithoutResult(ignored -> {
            // 唯一键处理首调用竞争，随后锁住分支行；PostgreSQL 不捕获唯一键异常以免事务失效。
            String insert = "INSERT INTO tcc_branches(tx_id,payload,state) VALUES (?,?,'NEW') "
                    + (resource == Resource.STOCK ? "ON DUPLICATE KEY UPDATE tx_id=tcc_branches.tx_id"
                    : "ON CONFLICT(tx_id) DO NOTHING");
            jdbc.update(insert, p.orderId(), payload);
            var branch = jdbc.queryForMap("SELECT payload,state FROM tcc_branches WHERE tx_id=? FOR UPDATE", p.orderId());
            if (!decode((String) branch.get("payload")).sameBusiness(p)) {
                throw new BusinessException("同一 TCC 分支编号的业务内容不同：" + p.orderId());
            }
            State state = State.valueOf((String) branch.get("state"));
            switch (action) {
                case TRY -> {
                    if (state == State.CANCELLED) throw new BusinessException("TCC 分支已取消，拒绝迟到 Try");
                    if (state != State.NEW) return;
                    reserve(jdbc, resource, p);
                    setState(jdbc, p, State.RESERVED);
                }
                case CONFIRM -> {
                    if (state == State.CONFIRMED) return;
                    if (state != State.RESERVED) throw new BusinessException("TCC 分支没有有效预留，不能 Confirm");
                    finish(jdbc, resource, p, true);
                    setState(jdbc, p, State.CONFIRMED);
                }
                case CANCEL -> {
                    if (state == State.CANCELLED) return;
                    if (state == State.CONFIRMED) throw new BusinessException("TCC 分支已确认，不能 Cancel");
                    if (state == State.RESERVED) finish(jdbc, resource, p, false);
                    // Cancel 先到也写墓碑，防止超时后的迟到 Try 再次冻结资源。
                    setState(jdbc, p, State.CANCELLED);
                }
            }
        });
    }

    private void reserve(JdbcTemplate jdbc, Resource resource, Purchase p) {
        Object amount = resource == Resource.STOCK ? p.quantity() : p.amount();
        int changed = jdbc.update("UPDATE " + table(resource)
                + " SET available=available-?,reserved=reserved+? WHERE " + key(resource)
                + "=? AND available>=?", amount, amount, resourceId(resource, p), amount);
        // 抛出异常回滚整个本地事务，资源不足不得留下 NEW 日志。
        if (changed != 1)
            throw new BusinessException(resource == Resource.STOCK ? "TCC 库存不足或商品不存在" : "TCC 余额不足或账户不存在");
    }

    private void finish(JdbcTemplate jdbc, Resource resource, Purchase p, boolean confirm) {
        Object amount = resource == Resource.STOCK ? p.quantity() : p.amount();
        String target = confirm ? "confirmed" : "available";
        int changed = jdbc.update("UPDATE " + table(resource) + " SET reserved=reserved-?,"
                        + target + "=" + target + "+? WHERE " + key(resource) + "=? AND reserved>=?",
                amount, amount, resourceId(resource, p), amount);
        if (changed != 1) throw new BusinessException("TCC 预留资源缺失，保留分支状态等待人工处理");
    }

    private void setState(JdbcTemplate jdbc, Purchase p, State state) {
        jdbc.update("UPDATE tcc_branches SET state=? WHERE tx_id=?", state.name(), p.orderId());
    }

    private String table(Resource resource) {
        return resource == Resource.STOCK ? "tcc_stock" : "tcc_accounts";
    }

    private String key(Resource resource) {
        return resource == Resource.STOCK ? "product_id" : "user_id";
    }

    private String resourceId(Resource resource, Purchase p) {
        return resource == Resource.STOCK ? p.productId() : p.userId();
    }

    private String encode(Purchase p) {
        try {
            return json.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 TCC 请求", e);
        }
    }

    private Purchase decode(String payload) {
        try {
            return json.readValue(payload, Purchase.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取 TCC 分支请求", e);
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        // 跨库快照用于观察最终一致性；不承诺两个查询处于同一隔离快照。
        return Map.of("stock", mysql.queryForList("SELECT * FROM tcc_stock ORDER BY product_id"),
                "accounts", pg.queryForList("SELECT * FROM tcc_accounts ORDER BY user_id"),
                "branches", Map.of("stock", mysql.queryForList("SELECT * FROM tcc_branches ORDER BY tx_id"),
                        "account", pg.queryForList("SELECT * FROM tcc_branches ORDER BY tx_id")));
    }
}

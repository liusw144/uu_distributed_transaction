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
 *
 * @author liusw144
 */
public class TccParticipant implements ProtocolParticipant {
    private static final String PAYLOAD_COLUMN = "payload";

    /**
     * 协调器请求参与者执行的协议动作。
     *
     * @author liusw144
     */
    private enum Action {
        /** 预留可用资源。 */
        TRY,
        /** 消耗已预留资源。 */
        CONFIRM,
        /** 释放预留资源或记录空回滚。 */
        CANCEL
    }

    /**
     * 与业务资源在同一事务中持久化的分支状态。
     *
     * @author liusw144
     */
    private enum State {
        /** 分支刚创建，尚未预留资源。 */
        NEW,
        /** 资源已冻结，等待确认或取消。 */
        RESERVED,
        /** 资源已确认消耗。 */
        CONFIRMED,
        /** 分支已取消，阻止迟到的预留请求。 */
        CANCELLED
    }

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgJdbcTemplate;
    private final TransactionTemplate mysqlTx;
    private final TransactionTemplate pgTx;
    private final ObjectMapper objectMapper;

    public TccParticipant(JdbcTemplate mysqlJdbcTemplate, JdbcTemplate pgJdbcTemplate,
            PlatformTransactionManager mysqlTx, PlatformTransactionManager pgTx, ObjectMapper objectMapper) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.mysqlTx = localTransaction(mysqlTx);
        this.pgTx = localTransaction(pgTx);
        this.objectMapper = objectMapper;
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager transactionManager) {
        var transactionTemplate = new TransactionTemplate(transactionManager);
        // 分支必须有自己的提交边界，不能误加入协调器或调用方的本地事务。
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }

    /**
     * 返回协调器用于路由的 TCC 协议标识。
     *
     * @return 协议标识
     */
    @Override
    public String protocol() {
        return "TCC";
    }

    /**
     * 执行 Try，在资源所在库中原子预留资源并记录分支状态。
     *
     * @param resource
     *            目标业务资源
     * @param purchase
     *            下单请求
     */
    @Override
    public void forward(Resource resource, Purchase purchase) {
        execute(resource, purchase, Action.TRY);
    }

    /**
     * 幂等确认已预留资源，拒绝没有有效预留的分支。
     *
     * @param resource
     *            目标业务资源
     * @param purchase
     *            下单请求
     */
    @Override
    public void confirm(Resource resource, Purchase purchase) {
        execute(resource, purchase, Action.CONFIRM);
    }

    /**
     * 释放已预留资源；空回滚同样记录取消状态以拦截迟到 Try。
     *
     * @param resource
     *            目标业务资源
     * @param purchase
     *            下单请求
     */
    @Override
    public void reverse(Resource resource, Purchase purchase) {
        execute(resource, purchase, Action.CANCEL);
    }

    private void execute(Resource resource, Purchase purchase, Action action) {
        JdbcTemplate jdbcTemplate = resource == Resource.STOCK ? mysqlJdbcTemplate : pgJdbcTemplate;
        TransactionTemplate transactionTemplate = resource == Resource.STOCK ? mysqlTx : pgTx;
        String payload = encode(purchase);
        transactionTemplate.executeWithoutResult(ignored -> {
            // 唯一键处理首调用竞争，随后锁住分支行；PostgreSQL 不捕获唯一键异常以免事务失效。
            String insertSql = "INSERT INTO tcc_branches(tx_id,payload,state) VALUES (?,?,'NEW') "
                    + (resource == Resource.STOCK
                            ? "ON DUPLICATE KEY UPDATE tx_id=tcc_branches.tx_id"
                            : "ON CONFLICT(tx_id) DO NOTHING");
            jdbcTemplate.update(insertSql, purchase.orderId(), payload);
            var storedBranch = jdbcTemplate
                    .queryForMap("SELECT payload,state FROM tcc_branches WHERE tx_id=? FOR UPDATE", purchase.orderId());
            if (!decode((String) storedBranch.get(PAYLOAD_COLUMN)).sameBusiness(purchase)) {
                throw new BusinessException("同一 TCC 分支编号的业务内容不同：" + purchase.orderId());
            }
            State state = State.valueOf((String) storedBranch.get("state"));
            switch (action) {
                case TRY -> {
                    if (state == State.CANCELLED) {
                        throw new BusinessException("TCC 分支已取消，拒绝迟到 Try");
                    }
                    if (state != State.NEW) {
                        return;
                    }
                    reserve(jdbcTemplate, resource, purchase);
                    setState(jdbcTemplate, purchase, State.RESERVED);
                }
                case CONFIRM -> {
                    if (state == State.CONFIRMED) {
                        return;
                    }
                    if (state != State.RESERVED) {
                        throw new BusinessException("TCC 分支没有有效预留，不能 Confirm");
                    }
                    finish(jdbcTemplate, resource, purchase, true);
                    setState(jdbcTemplate, purchase, State.CONFIRMED);
                }
                case CANCEL -> {
                    if (state == State.CANCELLED) {
                        return;
                    }
                    if (state == State.CONFIRMED) {
                        throw new BusinessException("TCC 分支已确认，不能 Cancel");
                    }
                    if (state == State.RESERVED) {
                        finish(jdbcTemplate, resource, purchase, false);
                    }
                    // Cancel 先到也写墓碑，防止超时后的迟到 Try 再次冻结资源。
                    setState(jdbcTemplate, purchase, State.CANCELLED);
                }
                default -> {
                    throw new IllegalArgumentException("不支持的 TCC 协议动作：" + action);
                }
            }
        });
    }

    private void reserve(JdbcTemplate jdbcTemplate, Resource resource, Purchase purchase) {
        Object amount = resource == Resource.STOCK ? purchase.quantity() : purchase.amount();
        int changed = jdbcTemplate.update("UPDATE " + table(resource)
                + " SET available=available-?,reserved=reserved+? WHERE " + key(resource) + "=? AND available>=?",
                amount, amount, resourceId(resource, purchase), amount);
        // 抛出异常回滚整个本地事务，资源不足不得留下 NEW 日志。
        if (changed != 1) {
            throw new BusinessException(resource == Resource.STOCK ? "TCC 库存不足或商品不存在" : "TCC 余额不足或账户不存在");
        }
    }

    private void finish(JdbcTemplate jdbcTemplate, Resource resource, Purchase purchase, boolean confirm) {
        Object amount = resource == Resource.STOCK ? purchase.quantity() : purchase.amount();
        String targetColumn = confirm ? "confirmed" : "available";
        int changed = jdbcTemplate.update(
                "UPDATE " + table(resource) + " SET reserved=reserved-?," + targetColumn + "=" + targetColumn
                        + "+? WHERE " + key(resource) + "=? AND reserved>=?",
                amount, amount, resourceId(resource, purchase), amount);
        if (changed != 1) {
            throw new BusinessException("TCC 预留资源缺失，保留分支状态等待人工处理");
        }
    }

    private void setState(JdbcTemplate jdbcTemplate, Purchase purchase, State state) {
        jdbcTemplate.update("UPDATE tcc_branches SET state=? WHERE tx_id=?", state.name(), purchase.orderId());
    }

    private String table(Resource resource) {
        return resource == Resource.STOCK ? "tcc_stock" : "tcc_accounts";
    }

    private String key(Resource resource) {
        return resource == Resource.STOCK ? "product_id" : "user_id";
    }

    private String resourceId(Resource resource, Purchase purchase) {
        return resource == Resource.STOCK ? purchase.productId() : purchase.userId();
    }

    private String encode(Purchase purchase) {
        try {
            return objectMapper.writeValueAsString(purchase);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 TCC 请求", exception);
        }
    }

    private Purchase decode(String payload) {
        try {
            return objectMapper.readValue(payload, Purchase.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取 TCC 分支请求", exception);
        }
    }

    /**
     * 查询两库的资源和分支日志，用于观察协议最终一致性。
     *
     * @return 不承诺跨库同一隔离快照的业务状态
     */
    @Override
    public Map<String, Object> snapshot() {
        // 跨库快照用于观察最终一致性；不承诺两个查询处于同一隔离快照。
        return Map.of("stock", mysqlJdbcTemplate.queryForList("SELECT * FROM tcc_stock ORDER BY product_id"),
                "accounts", pgJdbcTemplate.queryForList("SELECT * FROM tcc_accounts ORDER BY user_id"), "branches",
                Map.of("stock", mysqlJdbcTemplate.queryForList("SELECT * FROM tcc_branches ORDER BY tx_id"), "account",
                        pgJdbcTemplate.queryForList("SELECT * FROM tcc_branches ORDER BY tx_id")));
    }
}

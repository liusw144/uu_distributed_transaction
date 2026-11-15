package cn.demo.seata.saga;

import cn.demo.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正向/补偿均为独立本地事务：资源更新和动作流水一起提交。
 *
 * @author liusw144
 */
public class SagaParticipant {
    private static final String RESOURCE_ORDER = "ORDER";
    private static final String RESOURCE_STOCK = "STOCK";
    private static final String RESOURCE_ACCOUNT = "ACCOUNT";
    private static final String STATE_APPLIED = "APPLIED";
    private static final String STATE_COMPENSATED = "COMPENSATED";
    private static final String FIELD_FAIL_AT = "failAt";
    private static final String FIELD_PAYLOAD = "payload";

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgJdbcTemplate;
    private final TransactionTemplate mysqlTransactionTemplate;
    private final TransactionTemplate pgTransactionTemplate;
    private final Map<String, Boolean> faults = new ConcurrentHashMap<>();

    /**
     * 注入两个数据源并创建独立本地事务模板。
     *
     * @param mysqlDataSource
     *            MySQL 数据源
     * @param pgDataSource
     *            PostgreSQL 数据源
     */
    public SagaParticipant(DataSource mysqlDataSource, DataSource pgDataSource) {
        this.mysqlJdbcTemplate = new JdbcTemplate(mysqlDataSource);
        this.pgJdbcTemplate = new JdbcTemplate(pgDataSource);
        mysqlTransactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(mysqlDataSource));
        pgTransactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(pgDataSource));
        mysqlTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        pgTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 执行幂等订单动作。
     *
     * @param parameters
     *            状态机业务参数
     * @return 动作是否完成
     */
    public boolean order(Map<String, Object> parameters) {
        return execute(RESOURCE_ORDER, parameters, false);
    }

    /**
     * 执行幂等库存扣减动作。
     *
     * @param parameters
     *            状态机业务参数
     * @return 动作是否完成
     */
    public boolean stock(Map<String, Object> parameters) {
        return execute(RESOURCE_STOCK, parameters, false);
    }

    /**
     * 执行幂等余额扣减动作。
     *
     * @param parameters
     *            状态机业务参数
     * @return 动作是否完成
     */
    public boolean account(Map<String, Object> parameters) {
        return execute(RESOURCE_ACCOUNT, parameters, false);
    }

    /**
     * 补偿订单动作并持久化空补偿墓碑。
     *
     * @param parameters
     *            状态机业务参数
     * @return 补偿是否完成
     */
    public boolean undoOrder(Map<String, Object> parameters) {
        return execute(RESOURCE_ORDER, parameters, true);
    }

    /**
     * 幂等返还库存并持久化补偿流水。
     *
     * @param parameters
     *            状态机业务参数
     * @return 补偿是否完成
     */
    public boolean undoStock(Map<String, Object> parameters) {
        return execute(RESOURCE_STOCK, parameters, true);
    }

    /**
     * 幂等返还余额并持久化补偿流水。
     *
     * @param parameters
     *            状态机业务参数
     * @return 补偿是否完成
     */
    public boolean undoAccount(Map<String, Object> parameters) {
        return execute(RESOURCE_ACCOUNT, parameters, true);
    }

    /**
     * 在订单动作提交后检查演示故障点。
     *
     * @param parameters
     *            状态机业务参数
     * @return 未触发故障时返回 true
     */
    public boolean afterOrder(Map<String, Object> parameters) {
        return checkpoint(parameters, "AFTER_ORDER");
    }

    /**
     * 在库存动作提交后检查演示故障点。
     *
     * @param parameters
     *            状态机业务参数
     * @return 未触发故障时返回 true
     */
    public boolean afterStock(Map<String, Object> parameters) {
        return checkpoint(parameters, "AFTER_STOCK");
    }

    /**
     * 在账户动作提交后检查演示故障点。
     *
     * @param parameters
     *            状态机业务参数
     * @return 未触发故障时返回 true
     */
    public boolean afterAccount(Map<String, Object> parameters) {
        return checkpoint(parameters, "AFTER_ACCOUNT");
    }

    private boolean checkpoint(Map<String, Object> parameters, String point) {
        if (point.equals(parameters.get(FIELD_FAIL_AT))) {
            throw new BusinessException("模拟故障：" + point);
        }
        return true;
    }

    /**
     * 设置指定资源下一次补偿的失败或响应丢失故障。
     *
     * @param orderId
     *            业务订单编号
     * @param resource
     *            业务资源名称
     * @param loseAck
     *            是否在补偿提交后模拟响应丢失
     */
    public void failNext(String orderId, String resource, boolean loseAck) {
        if (!Set.of(RESOURCE_ORDER, RESOURCE_STOCK, RESOURCE_ACCOUNT).contains(resource)) {
            throw new IllegalArgumentException("资源必须为 ORDER/STOCK/ACCOUNT");
        }
        faults.put(orderId + ":" + resource, loseAck);
    }

    private boolean execute(String resource, Map<String, Object> parameters, boolean undo) {
        String orderId = parameters.get("orderId").toString();
        Boolean fault = undo ? faults.remove(orderId + ":" + resource) : null;
        if (Boolean.FALSE.equals(fault)) {
            throw new BusinessException("模拟补偿失败：" + resource);
        }
        boolean accountResource = resource.equals(RESOURCE_ACCOUNT);
        JdbcTemplate jdbcTemplate = accountResource ? pgJdbcTemplate : mysqlJdbcTemplate;
        String table = "seata_saga_" + resource.toLowerCase(Locale.ROOT) + "_actions";
        String payload = parameters.get(FIELD_PAYLOAD).toString();
        (accountResource ? pgTransactionTemplate : mysqlTransactionTemplate).executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO " + table + "(tx_id,payload,state) VALUES (?,?,'NEW')"
                    + (accountResource ? " ON CONFLICT(tx_id) DO NOTHING" : " ON DUPLICATE KEY UPDATE tx_id=tx_id"),
                    orderId, payload);
            Map<String, Object> row = jdbcTemplate
                    .queryForMap("SELECT payload,state FROM " + table + " WHERE tx_id=? FOR UPDATE", orderId);
            if (!payload.equals(row.get(FIELD_PAYLOAD))) {
                throw new BusinessException("动作编号已用于不同业务");
            }
            String state = row.get("state").toString();
            if (undo) {
                if (state.equals(STATE_COMPENSATED)) {
                    return;
                }
                if (state.equals(STATE_APPLIED)) {
                    change(jdbcTemplate, resource, parameters, true);
                }
                // NEW 也写墓碑，支持空补偿并阻止迟到正向动作。
                jdbcTemplate.update("UPDATE " + table + " SET state='COMPENSATED' WHERE tx_id=?", orderId);
            } else {
                if (state.equals(STATE_APPLIED)) {
                    return;
                }
                if (state.equals(STATE_COMPENSATED)) {
                    throw new BusinessException("拒绝已补偿的迟到动作");
                }
                change(jdbcTemplate, resource, parameters, false);
                jdbcTemplate.update("UPDATE " + table + " SET state='APPLIED' WHERE tx_id=?", orderId);
            }
        });
        if (Boolean.TRUE.equals(fault)) {
            throw new BusinessException("补偿已经提交，但模拟响应丢失：" + resource);
        }
        return true;
    }

    private void change(JdbcTemplate jdbcTemplate, String resource, Map<String, Object> parameters, boolean undo) {
        if (resource.equals(RESOURCE_ORDER)) {
            return;
        } // 订单流水本身就是订单状态。
        int updatedRows;
        if (resource.equals(RESOURCE_STOCK)) {
            int quantity = ((Number) parameters.get("quantity")).intValue();
            updatedRows = undo
                    ? jdbcTemplate.update("UPDATE seata_saga_stock SET available=available+? WHERE id=?", quantity,
                            parameters.get("productId"))
                    : jdbcTemplate.update(
                            "UPDATE seata_saga_stock SET available=available-? WHERE id=? AND available>=?", quantity,
                            parameters.get("productId"), quantity);
        } else {
            BigDecimal amount = new BigDecimal(parameters.get("amount").toString());
            updatedRows = undo
                    ? jdbcTemplate.update("UPDATE seata_saga_account SET balance=balance+? WHERE id=?", amount,
                            parameters.get("userId"))
                    : jdbcTemplate.update("UPDATE seata_saga_account SET balance=balance-? WHERE id=? AND balance>=?",
                            amount, parameters.get("userId"), amount);
        }
        if (updatedRows != 1) {
            throw new BusinessException(undo ? "补偿资源不存在" : "库存或余额不足/资源不存在");
        }
    }

    /**
     * 查询指定商品当前可用库存。
     *
     * @param productId
     *            商品编号
     * @return 可用库存数量
     */
    public int stockAvailable(String productId) {
        return mysqlJdbcTemplate.queryForObject("SELECT available FROM seata_saga_stock WHERE id=?", Integer.class,
                productId);
    }

    /**
     * 查询指定账户当前余额。
     *
     * @param userId
     *            账户编号
     * @return 账户余额
     */
    public BigDecimal balance(String userId) {
        return pgJdbcTemplate.queryForObject("SELECT balance FROM seata_saga_account WHERE id=?", BigDecimal.class,
                userId);
    }

    /**
     * 查询指定订单动作的持久化状态。
     *
     * @param orderId
     *            业务订单编号
     * @return 订单动作状态
     */
    public String orderState(String orderId) {
        return mysqlJdbcTemplate.queryForObject("SELECT state FROM seata_saga_order_actions WHERE tx_id=?",
                String.class, orderId);
    }

    /**
     * 测试和教学时显式准备独立资源；应用启动只插入缺失的 p1/u1，不重置现有值。
     *
     * @param product
     *            商品编号
     * @param user
     *            账户编号
     * @param stock
     *            初始库存
     * @param balance
     *            初始余额
     */
    void seed(String product, String user, int stock, BigDecimal balance) {
        mysqlJdbcTemplate.update(
                "INSERT INTO seata_saga_stock(id,available) VALUES (?,?) ON DUPLICATE KEY UPDATE available=?", product,
                stock, stock);
        pgJdbcTemplate.update("INSERT INTO seata_saga_account(id,balance) VALUES (?,?) "
                + "ON CONFLICT(id) DO UPDATE SET balance=excluded.balance", user, balance);
    }

    /**
     * 查询各数据库当前资源及动作状态。
     *
     * @return 各资源的观察状态
     */
    public Map<String, Object> snapshot() {
        return Map.of("stock", mysqlJdbcTemplate.queryForList("SELECT * FROM seata_saga_stock ORDER BY id"), "accounts",
                pgJdbcTemplate.queryForList("SELECT * FROM seata_saga_account ORDER BY id"), "orders",
                mysqlJdbcTemplate.queryForList("SELECT * FROM seata_saga_order_actions ORDER BY tx_id"), "stockActions",
                mysqlJdbcTemplate.queryForList("SELECT * FROM seata_saga_stock_actions ORDER BY tx_id"),
                "accountActions",
                pgJdbcTemplate.queryForList("SELECT * FROM seata_saga_account_actions ORDER BY tx_id"));
    }
}

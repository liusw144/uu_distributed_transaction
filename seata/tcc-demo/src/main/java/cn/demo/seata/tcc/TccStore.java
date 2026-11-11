package cn.demo.seata.tcc;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 在所属数据库的本地事务中持久化 TCC 分支并完成资源预留与释放。
 *
 * @author liusw144
 */
@Component
public class TccStore {
    private static final String STATE_NEW = "NEW";
    private static final String STATE_PREPARED = "PREPARED";
    private static final String STATE_CONFIRMED = "CONFIRMED";
    private static final String STATE_CANCELLED = "CANCELLED";
    private static final String PHASE_CONFIRM = "CONFIRM";
    private static final String PHASE_CANCEL = "CANCEL";
    private static final String COLUMN_STATE = "state";
    private static final String COLUMN_ORDER_ID = "order_id";
    private static final String COLUMN_USER_ID = "user_id";
    private static final String COLUMN_PRODUCT_ID = "product_id";
    private static final String COLUMN_QUANTITY = "quantity";
    private static final String COLUMN_UNIT_PRICE = "unit_price";
    private static final String COLUMN_AMOUNT = "amount";

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgJdbcTemplate;
    private final TransactionTemplate mysqlTransactionTemplate;
    private final TransactionTemplate pgTransactionTemplate;
    private final PhaseFaults faults;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param mysqlJdbcTemplate
     *            MySQL 数据访问模板
     * @param pgJdbcTemplate
     *            PostgreSQL 数据访问模板
     * @param mysqlTransactionManager
     *            MySQL 本地事务管理器
     * @param pgTransactionManager
     *            PostgreSQL 本地事务管理器
     * @param faults
     *            二阶段故障计数器
     */
    public TccStore(@Qualifier("mysqlJdbc") JdbcTemplate mysqlJdbcTemplate,
            @Qualifier("pgJdbc") JdbcTemplate pgJdbcTemplate,
            @Qualifier("mysqlTx") DataSourceTransactionManager mysqlTransactionManager,
            @Qualifier("pgTx") DataSourceTransactionManager pgTransactionManager, PhaseFaults faults) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.mysqlTransactionTemplate = new TransactionTemplate(mysqlTransactionManager);
        this.pgTransactionTemplate = new TransactionTemplate(pgTransactionManager);
        this.faults = faults;
        // 建表和种子均幂等，不覆盖其他运行已消费的资源；可使用相同 SQL 手动初始化。
        new ResourceDatabasePopulator(new ClassPathResource("mysql.sql"))
                .execute(Objects.requireNonNull(mysqlJdbcTemplate.getDataSource()));
        new ResourceDatabasePopulator(new ClassPathResource("postgresql.sql"))
                .execute(Objects.requireNonNull(pgJdbcTemplate.getDataSource()));
    }

    /**
     * 校验重复请求并返回已确认订单的原事务信息。
     *
     * @param purchase
     *            购买请求
     * @return 原事务信息；订单不存在时返回 null
     */
    public Map<String, Object> existing(Purchase purchase) {
        List<Map<String, Object>> rows = mysqlJdbcTemplate
                .queryForList("SELECT * FROM seata_tcc_orders WHERE order_id=?", purchase.orderId());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (!purchase.userId().equals(row.get(COLUMN_USER_ID))
                || !purchase.productId().equals(row.get(COLUMN_PRODUCT_ID))
                || purchase.quantity() != ((Number) row.get(COLUMN_QUANTITY)).intValue()
                || purchase.unitPrice().compareTo((BigDecimal) row.get(COLUMN_UNIT_PRICE)) != 0) {
            throw new BusinessException("重复业务编号内容冲突");
        }
        if (!STATE_CONFIRMED.equals(row.get(COLUMN_STATE))) {
            throw new BusinessException("业务编号已占用，当前状态：" + row.get(COLUMN_STATE) + "，请查询原事务");
        }
        // 一库确认不等于跨库完成，重复查询仍返回原事务，而不创建新扣款。
        return Map.of("orderId", purchase.orderId(), "xid", row.get("xid"), "status", "REPLAY", "orderState",
                row.get(COLUMN_STATE));
    }

    private String table(boolean orderBranch) {
        return orderBranch ? "seata_tcc_order_branch" : "seata_tcc_account_branch";
    }

    private Map<String, Object> lock(JdbcTemplate jdbcTemplate, boolean orderBranch,
            BusinessActionContext actionContext, String initialState) {
        if (actionContext == null || actionContext.getXid() == null || actionContext.getBranchId() <= 0) {
            throw new IllegalArgumentException("缺少 Seata 分支上下文");
        }
        String insert = "INSERT " + (orderBranch ? "IGNORE " : "") + "INTO " + table(orderBranch)
                + "(xid,branch_id,state) VALUES (?,?,?)" + (orderBranch ? "" : " ON CONFLICT DO NOTHING");
        jdbcTemplate.update(insert, actionContext.getXid(), actionContext.getBranchId(), initialState);
        // 同库行锁串行化 Try/Confirm/Cancel；空取消先插入墓碑，晚到 Try 永远不能再预留。
        return jdbcTemplate.queryForMap(
                "SELECT * FROM " + table(orderBranch) + " WHERE xid=? AND branch_id=? FOR UPDATE",
                actionContext.getXid(), actionContext.getBranchId());
    }

    private boolean prepare(BusinessActionContext actionContext, Purchase purchase, boolean orderBranch) {
        JdbcTemplate jdbcTemplate = orderBranch ? mysqlJdbcTemplate : pgJdbcTemplate;
        return Boolean.TRUE
                .equals((orderBranch ? mysqlTransactionTemplate : pgTransactionTemplate).execute(transactionStatus -> {
                    Map<String, Object> branch = lock(jdbcTemplate, orderBranch, actionContext, STATE_NEW);
                    if (STATE_CANCELLED.equals(branch.get(COLUMN_STATE))) {
                        throw new BusinessException("拒绝晚到 Try：分支已取消");
                    }
                    if (!STATE_NEW.equals(branch.get(COLUMN_STATE))) {
                        if (!purchase.orderId().equals(branch.get(COLUMN_ORDER_ID))
                                || !purchase.userId().equals(branch.get(COLUMN_USER_ID))
                                || !purchase.productId().equals(branch.get(COLUMN_PRODUCT_ID))
                                || purchase.quantity() != ((Number) branch.get(COLUMN_QUANTITY)).intValue()
                                || purchase.unitPrice().compareTo((BigDecimal) branch.get(COLUMN_UNIT_PRICE)) != 0) {
                            throw new BusinessException("重复分支内容冲突");
                        }
                        return true;
                    }
                    if (orderBranch) {
                        // 订单与库存同属 MySQL，一个动作本地事务保证一起预留或一起回滚。
                        mysqlJdbcTemplate.update(
                                "INSERT INTO seata_tcc_orders(order_id,user_id,product_id,"
                                        + "quantity,unit_price,amount,state,xid,branch_id) "
                                        + "VALUES (?,?,?,?,?,?,'PREPARED',?,?)",
                                purchase.orderId(), purchase.userId(), purchase.productId(), purchase.quantity(),
                                purchase.unitPrice(), purchase.amount(), actionContext.getXid(),
                                actionContext.getBranchId());
                        purchase.failIf(Purchase.Failure.AFTER_ORDER);
                        if (mysqlJdbcTemplate.update(
                                "UPDATE seata_tcc_stock SET available=available-?,frozen=frozen+? "
                                        + "WHERE product_id=? AND available>=?",
                                purchase.quantity(), purchase.quantity(), purchase.productId(),
                                purchase.quantity()) != 1) {
                            throw new BusinessException("库存不足或商品不存在");
                        }
                    } else {
                        if (pgJdbcTemplate.update(
                                "UPDATE seata_tcc_account SET available=available-?,frozen=frozen+? "
                                        + "WHERE user_id=? AND available>=?",
                                purchase.amount(), purchase.amount(), purchase.userId(), purchase.amount()) != 1) {
                            throw new BusinessException("余额不足或用户不存在");
                        }
                    }
                    jdbcTemplate.update(
                            "UPDATE " + table(orderBranch) + " SET state='PREPARED',order_id=?,user_id=?,product_id=?,"
                                    + "quantity=?,amount=?,unit_price=? WHERE xid=? AND branch_id=?",
                            purchase.orderId(), purchase.userId(), purchase.productId(), purchase.quantity(),
                            purchase.amount(), purchase.unitPrice(), actionContext.getXid(),
                            actionContext.getBranchId());
                    return true;
                }));
    }

    /**
     * 在订单库中预留订单和库存。
     *
     * @param actionContext
     *            Seata 分支上下文
     * @param purchase
     *            购买请求
     * @return 资源预留是否成功
     */
    public boolean tryOrder(BusinessActionContext actionContext, Purchase purchase) {
        return prepare(actionContext, purchase, true);
    }

    /**
     * 在账户库中预留余额。
     *
     * @param actionContext
     *            Seata 分支上下文
     * @param purchase
     *            购买请求
     * @return 资源预留是否成功
     */
    public boolean tryAccount(BusinessActionContext actionContext, Purchase purchase) {
        return prepare(actionContext, purchase, false);
    }

    /**
     * 在所属数据库本地事务中幂等完成 TCC 确认或取消。
     *
     * @param actionContext
     *            Seata 分支上下文
     * @param orderBranch
     *            是否为订单与库存分支
     * @param confirm
     *            是否执行确认
     * @return 分支是否完成；尚不支持完成或模拟故障时返回 false
     */
    public boolean finish(BusinessActionContext actionContext, boolean orderBranch, boolean confirm) {
        JdbcTemplate jdbcTemplate = orderBranch ? mysqlJdbcTemplate : pgJdbcTemplate;
        return Boolean.TRUE
                .equals((orderBranch ? mysqlTransactionTemplate : pgTransactionTemplate).execute(transactionStatus -> {
                    Map<String, Object> branch = lock(jdbcTemplate, orderBranch, actionContext,
                            confirm ? STATE_NEW : STATE_CANCELLED);
                    String targetState = confirm ? STATE_CONFIRMED : STATE_CANCELLED;
                    if (targetState.equals(branch.get(COLUMN_STATE))) {
                        return true;
                    }
                    if (!STATE_PREPARED.equals(branch.get(COLUMN_STATE))) {
                        return false;
                    }
                    if (faults.fail((String) branch.get(COLUMN_ORDER_ID), confirm ? PHASE_CONFIRM : PHASE_CANCEL)) {
                        return false;
                    }
                    int updatedRows;
                    if (orderBranch) {
                        int quantity = ((Number) branch.get(COLUMN_QUANTITY)).intValue();
                        updatedRows = mysqlJdbcTemplate.update(
                                "UPDATE seata_tcc_stock SET frozen=frozen-?,available=available+? "
                                        + "WHERE product_id=? AND frozen>=?",
                                quantity, confirm ? 0 : quantity, branch.get(COLUMN_PRODUCT_ID), quantity);
                        mysqlJdbcTemplate.update("UPDATE seata_tcc_orders SET state=? WHERE order_id=?", targetState,
                                branch.get(COLUMN_ORDER_ID));
                    } else {
                        BigDecimal amount = (BigDecimal) branch.get(COLUMN_AMOUNT);
                        updatedRows = pgJdbcTemplate.update(
                                "UPDATE seata_tcc_account SET frozen=frozen-?,available=available+? "
                                        + "WHERE user_id=? AND frozen>=?",
                                amount, confirm ? BigDecimal.ZERO : amount, branch.get(COLUMN_USER_ID), amount);
                    }
                    if (updatedRows != 1) {
                        throw new IllegalStateException("冻结资源丢失，拒绝标记二阶段完成");
                    }
                    jdbcTemplate.update("UPDATE " + table(orderBranch) + " SET state=? WHERE xid=? AND branch_id=?",
                            targetState, actionContext.getXid(), actionContext.getBranchId());
                    return true;
                }));
    }

    /**
     * 查询各数据库当前资源及动作状态。
     *
     * @return 各资源的观察状态
     */
    public Map<String, Object> snapshot() {
        // 跨库查询是两个时点的观察快照，不构成分布式只读事务。
        return Map.of("orders", mysqlJdbcTemplate.queryForList("SELECT * FROM seata_tcc_orders ORDER BY order_id"),
                "stock", mysqlJdbcTemplate.queryForList("SELECT * FROM seata_tcc_stock ORDER BY product_id"),
                "accounts", pgJdbcTemplate.queryForList("SELECT * FROM seata_tcc_account ORDER BY user_id"),
                "orderBranches",
                mysqlJdbcTemplate.queryForList("SELECT * FROM seata_tcc_order_branch ORDER BY branch_id"),
                "accountBranches",
                pgJdbcTemplate.queryForList("SELECT * FROM seata_tcc_account_branch ORDER BY branch_id"),
                "atomicSnapshot", false);
    }
}

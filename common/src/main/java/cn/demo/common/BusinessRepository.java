package cn.demo.common;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 执行订单、库存与账户 SQL；调用方必须选择正确的本地、JTA 或 Seata 事务边界。
 *
 * @author liusw144
 */
public class BusinessRepository {
    private static final String PAYMENT_PAID = "PAID";
    private static final String PAYMENT_REJECTED = "REJECTED";
    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate postgresqlJdbcTemplate;

    public BusinessRepository(JdbcTemplate mysqlJdbcTemplate, JdbcTemplate postgresqlJdbcTemplate) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.postgresqlJdbcTemplate = postgresqlJdbcTemplate;
    }

    /** 在调用方事务中创建指定方案的订单。 */
    public void insertOrder(String scenario, Purchase purchase, String status) {
        mysqlJdbcTemplate.update(
                "INSERT INTO demo_orders(order_id,scenario,user_id,product_id,quantity,amount,status) "
                        + "VALUES (?,?,?,?,?,?,?)",
                purchase.orderId(), scenario, purchase.userId(), purchase.productId(), purchase.quantity(),
                purchase.amount(), status);
    }

    /** 通过条件更新扣减库存，库存不足时拒绝业务。 */
    public void reduceStock(String scenario, Purchase purchase) {
        int affectedRows = mysqlJdbcTemplate.update(
                "UPDATE demo_stock SET available=available-? WHERE id=? AND available>=?", purchase.quantity(),
                scenario + ":" + purchase.productId(), purchase.quantity());
        if (affectedRows != 1) {
            throw new BusinessException("库存不足或商品不存在");
        }
    }

    /** 尝试扣减账户余额，并返回账户是否满足扣款条件。 */
    public boolean debit(String scenario, Purchase purchase) {
        return postgresqlJdbcTemplate.update("UPDATE demo_accounts SET balance=balance-? WHERE id=? AND balance>=?",
                purchase.amount(), scenario + ":" + purchase.userId(), purchase.amount()) == 1;
    }

    /** 扣减账户余额，扣款失败时抛出业务异常。 */
    public void requireDebit(String scenario, Purchase purchase) {
        if (!debit(scenario, purchase)) {
            throw new BusinessException("余额不足或账户不存在");
        }
    }

    /** 按消费端扣款结果结束 Outbox 订单，拒绝时原子释放库存。 */
    public void finishOutbox(Purchase purchase, String result) {
        if (!Set.of(PAYMENT_PAID, PAYMENT_REJECTED).contains(result)) {
            throw new BusinessException("未知扣款结果");
        }
        String status = PAYMENT_PAID.equals(result) ? PAYMENT_PAID : "CANCELLED";
        int affectedRows = mysqlJdbcTemplate.update(
                "UPDATE demo_orders SET status=? WHERE order_id=? AND scenario='OUTBOX' AND status='PENDING'", status,
                purchase.orderId());
        // 状态 CAS 与释放库存在同一 MySQL 事务中，重复确认不会重复加库存。
        if (affectedRows == 1 && PAYMENT_REJECTED.equals(result)) {
            mysqlJdbcTemplate.update("UPDATE demo_stock SET available=available+? WHERE id=?", purchase.quantity(),
                    "OUTBOX:" + purchase.productId());
        }
    }

    /** 查询指定方案最近创建的订单。 */
    public List<Map<String, Object>> orders(String scenario) {
        return mysqlJdbcTemplate.queryForList(
                "SELECT * FROM demo_orders WHERE scenario=? ORDER BY created_at DESC LIMIT 100", scenario);
    }

    /** 查询指定方案的订单、库存和账户状态。 */
    public Map<String, Object> snapshot(String scenario) {
        return Map.of("orders", orders(scenario), "stock",
                mysqlJdbcTemplate.queryForList("SELECT * FROM demo_stock WHERE id LIKE ?", scenario + ":%"), "accounts",
                postgresqlJdbcTemplate.queryForList("SELECT * FROM demo_accounts WHERE id LIKE ?", scenario + ":%"));
    }
}

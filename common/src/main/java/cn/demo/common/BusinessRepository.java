package cn.demo.common;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 此类只执行 SQL；调用方必须选择正确的本地、JTA 或 Seata 事务边界。
 */
public class BusinessRepository {
    private final JdbcTemplate mysql, pg;

    public BusinessRepository(JdbcTemplate mysql, JdbcTemplate pg) {
        this.mysql = mysql;
        this.pg = pg;
    }

    public void insertOrder(String scenario, Purchase p, String status) {
        mysql.update("INSERT INTO demo_orders(order_id,scenario,user_id,product_id,quantity,amount,status) VALUES (?,?,?,?,?,?,?)", p.orderId(), scenario, p.userId(), p.productId(), p.quantity(), p.amount(), status);
    }

    public void reduceStock(String scenario, Purchase p) {
        int n = mysql.update("UPDATE demo_stock SET available=available-? WHERE id=? AND available>=?", p.quantity(), scenario + ":" + p.productId(), p.quantity());
        if (n != 1) throw new BusinessException("库存不足或商品不存在");
    }

    public boolean debit(String scenario, Purchase p) {
        return pg.update("UPDATE demo_accounts SET balance=balance-? WHERE id=? AND balance>=?", p.amount(), scenario + ":" + p.userId(), p.amount()) == 1;
    }

    public void requireDebit(String scenario, Purchase p) {
        if (!debit(scenario, p)) throw new BusinessException("余额不足或账户不存在");
    }

    public void finishOutbox(Purchase p, String result) {
        if (!Set.of("PAID", "REJECTED").contains(result)) throw new BusinessException("未知扣款结果");
        String status = "PAID".equals(result) ? "PAID" : "CANCELLED";
        int n = mysql.update("UPDATE demo_orders SET status=? WHERE order_id=? AND scenario='OUTBOX' AND status='PENDING'", status, p.orderId());
        // 状态 CAS 与释放库存在同一 MySQL 事务中，重复确认不会重复加库存。
        if (n == 1 && "REJECTED".equals(result))
            mysql.update("UPDATE demo_stock SET available=available+? WHERE id=?", p.quantity(), "OUTBOX:" + p.productId());
    }

    public List<Map<String, Object>> orders(String scenario) {
        return mysql.queryForList("SELECT * FROM demo_orders WHERE scenario=? ORDER BY created_at DESC LIMIT 100", scenario);
    }

    public Map<String, Object> snapshot(String scenario) {
        return Map.of("orders", orders(scenario), "stock", mysql.queryForList("SELECT * FROM demo_stock WHERE id LIKE ?", scenario + ":%"), "accounts", pg.queryForList("SELECT * FROM demo_accounts WHERE id LIKE ?", scenario + ":%"));
    }
}

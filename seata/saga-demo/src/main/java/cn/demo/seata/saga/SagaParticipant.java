package cn.demo.seata.saga;

import cn.demo.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正向/补偿均为独立本地事务：资源更新和动作流水一起提交。
 */
public class SagaParticipant {
    private final JdbcTemplate mysql, pg;
    private final TransactionTemplate mysqlTx, pgTx;
    private final Map<String, Boolean> faults = new ConcurrentHashMap<>();

    public SagaParticipant(DataSource mysql, DataSource pg) {
        this.mysql = new JdbcTemplate(mysql);
        this.pg = new JdbcTemplate(pg);
        mysqlTx = new TransactionTemplate(new DataSourceTransactionManager(mysql));
        pgTx = new TransactionTemplate(new DataSourceTransactionManager(pg));
        mysqlTx.setPropagationBehavior(3);
        pgTx.setPropagationBehavior(3);
    }

    public boolean order(Map<String, Object> p) {
        return execute("ORDER", p, false);
    }

    public boolean stock(Map<String, Object> p) {
        return execute("STOCK", p, false);
    }

    public boolean account(Map<String, Object> p) {
        return execute("ACCOUNT", p, false);
    }

    public boolean undoOrder(Map<String, Object> p) {
        return execute("ORDER", p, true);
    }

    public boolean undoStock(Map<String, Object> p) {
        return execute("STOCK", p, true);
    }

    public boolean undoAccount(Map<String, Object> p) {
        return execute("ACCOUNT", p, true);
    }

    public boolean afterOrder(Map<String, Object> p) {
        return checkpoint(p, "AFTER_ORDER");
    }

    public boolean afterStock(Map<String, Object> p) {
        return checkpoint(p, "AFTER_STOCK");
    }

    public boolean afterAccount(Map<String, Object> p) {
        return checkpoint(p, "AFTER_ACCOUNT");
    }

    private boolean checkpoint(Map<String, Object> p, String point) {
        if (point.equals(p.get("failAt"))) throw new BusinessException("模拟故障：" + point);
        return true;
    }

    public void failNext(String id, String resource, boolean loseAck) {
        if (!Set.of("ORDER", "STOCK", "ACCOUNT").contains(resource))
            throw new IllegalArgumentException("资源必须为 ORDER/STOCK/ACCOUNT");
        faults.put(id + ":" + resource, loseAck);
    }

    private boolean execute(String resource, Map<String, Object> p, boolean undo) {
        String id = p.get("orderId").toString();
        Boolean fault = undo ? faults.remove(id + ":" + resource) : null;
        if (Boolean.FALSE.equals(fault)) throw new BusinessException("模拟补偿失败：" + resource);
        boolean account = resource.equals("ACCOUNT");
        JdbcTemplate db = account ? pg : mysql;
        String table = "seata_saga_" + resource.toLowerCase(Locale.ROOT) + "_actions";
        String payload = p.get("payload").toString();
        (account ? pgTx : mysqlTx).executeWithoutResult(status -> {
            db.update("INSERT INTO " + table + "(tx_id,payload,state) VALUES (?,?,'NEW')" +
                    (account ? " ON CONFLICT(tx_id) DO NOTHING" : " ON DUPLICATE KEY UPDATE tx_id=tx_id"), id, payload);
            var row = db.queryForMap("SELECT payload,state FROM " + table + " WHERE tx_id=? FOR UPDATE", id);
            if (!payload.equals(row.get("payload"))) throw new BusinessException("动作编号已用于不同业务");
            String state = row.get("state").toString();
            if (undo) {
                if (state.equals("COMPENSATED")) return;
                if (state.equals("APPLIED")) change(db, resource, p, true);
                // NEW 也写墓碑，支持空补偿并阻止迟到正向动作。
                db.update("UPDATE " + table + " SET state='COMPENSATED' WHERE tx_id=?", id);
            } else {
                if (state.equals("APPLIED")) return;
                if (state.equals("COMPENSATED")) throw new BusinessException("拒绝已补偿的迟到动作");
                change(db, resource, p, false);
                db.update("UPDATE " + table + " SET state='APPLIED' WHERE tx_id=?", id);
            }
        });
        if (Boolean.TRUE.equals(fault)) throw new BusinessException("补偿已经提交，但模拟响应丢失：" + resource);
        return true;
    }

    private void change(JdbcTemplate db, String resource, Map<String, Object> p, boolean undo) {
        if (resource.equals("ORDER")) return; // 订单流水本身就是订单状态。
        int n;
        if (resource.equals("STOCK")) {
            int count = ((Number) p.get("quantity")).intValue();
            n = undo ? db.update("UPDATE seata_saga_stock SET available=available+? WHERE id=?", count, p.get("productId")) :
                    db.update("UPDATE seata_saga_stock SET available=available-? WHERE id=? AND available>=?", count, p.get("productId"), count);
        } else {
            BigDecimal amount = new BigDecimal(p.get("amount").toString());
            n = undo ? db.update("UPDATE seata_saga_account SET balance=balance+? WHERE id=?", amount, p.get("userId")) :
                    db.update("UPDATE seata_saga_account SET balance=balance-? WHERE id=? AND balance>=?", amount, p.get("userId"), amount);
        }
        if (n != 1) throw new BusinessException(undo ? "补偿资源不存在" : "库存或余额不足/资源不存在");
    }

    public int stockAvailable(String id) {
        return mysql.queryForObject("SELECT available FROM seata_saga_stock WHERE id=?", Integer.class, id);
    }

    public BigDecimal balance(String id) {
        return pg.queryForObject("SELECT balance FROM seata_saga_account WHERE id=?", BigDecimal.class, id);
    }

    public String orderState(String id) {
        return mysql.queryForObject("SELECT state FROM seata_saga_order_actions WHERE tx_id=?", String.class, id);
    }

    // 测试和教学时显式准备独立资源；应用启动只插入缺失的 p1/u1，不重置现有值。
    void seed(String product, String user, int stock, BigDecimal balance) {
        mysql.update("INSERT INTO seata_saga_stock(id,available) VALUES (?,?) ON DUPLICATE KEY UPDATE available=?", product, stock, stock);
        pg.update("INSERT INTO seata_saga_account(id,balance) VALUES (?,?) ON CONFLICT(id) DO UPDATE SET balance=excluded.balance", user, balance);
    }

    public Map<String, Object> snapshot() {
        return Map.of("stock", mysql.queryForList("SELECT * FROM seata_saga_stock ORDER BY id"),
                "accounts", pg.queryForList("SELECT * FROM seata_saga_account ORDER BY id"),
                "orders", mysql.queryForList("SELECT * FROM seata_saga_order_actions ORDER BY tx_id"),
                "stockActions", mysql.queryForList("SELECT * FROM seata_saga_stock_actions ORDER BY tx_id"),
                "accountActions", pg.queryForList("SELECT * FROM seata_saga_account_actions ORDER BY tx_id"));
    }
}

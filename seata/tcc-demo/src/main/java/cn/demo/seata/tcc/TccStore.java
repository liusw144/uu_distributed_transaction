package cn.demo.seata.tcc;

import cn.demo.common.*;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

@Component
public class TccStore {
    private final JdbcTemplate mysql, pg;
    private final TransactionTemplate mysqlTx, pgTx;
    private final PhaseFaults faults;

    public TccStore(@Qualifier("mysqlJdbc") JdbcTemplate mysql, @Qualifier("pgJdbc") JdbcTemplate pg,
                    @Qualifier("mysqlTx") DataSourceTransactionManager mt, @Qualifier("pgTx") DataSourceTransactionManager pt, PhaseFaults faults) {
        this.mysql = mysql;
        this.pg = pg;
        this.mysqlTx = new TransactionTemplate(mt);
        this.pgTx = new TransactionTemplate(pt);
        this.faults = faults;
        // 建表和种子均幂等，不覆盖其他运行已消费的资源；可使用相同 SQL 手动初始化。
        new ResourceDatabasePopulator(new ClassPathResource("mysql.sql")).execute(Objects.requireNonNull(mysql.getDataSource()));
        new ResourceDatabasePopulator(new ClassPathResource("postgresql.sql")).execute(Objects.requireNonNull(pg.getDataSource()));
    }

    public Map<String, Object> existing(Purchase p) {
        var rows = mysql.queryForList("SELECT * FROM seata_tcc_orders WHERE order_id=?", p.orderId());
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        if (!p.userId().equals(row.get("user_id")) || !p.productId().equals(row.get("product_id"))
                || p.quantity() != ((Number) row.get("quantity")).intValue() || p.unitPrice().compareTo((BigDecimal) row.get("unit_price")) != 0)
            throw new BusinessException("重复业务编号内容冲突");
        if (!"CONFIRMED".equals(row.get("state")))
            throw new BusinessException("业务编号已占用，当前状态：" + row.get("state") + "，请查询原事务");
        // 一库确认不等于跨库完成，重复查询仍返回原事务，而不创建新扣款。
        return Map.of("orderId", p.orderId(), "xid", row.get("xid"), "status", "REPLAY", "orderState", row.get("state"));
    }

    private String table(boolean order) {
        return order ? "seata_tcc_order_branch" : "seata_tcc_account_branch";
    }

    private Map<String, Object> lock(JdbcTemplate db, boolean order, BusinessActionContext c, String initial) {
        if (c == null || c.getXid() == null || c.getBranchId() <= 0)
            throw new IllegalArgumentException("缺少 Seata 分支上下文");
        String insert = "INSERT " + (order ? "IGNORE " : "") + "INTO " + table(order) + "(xid,branch_id,state) VALUES (?,?,?)" + (order ? "" : " ON CONFLICT DO NOTHING");
        db.update(insert, c.getXid(), c.getBranchId(), initial);
        // 同库行锁串行化 Try/Confirm/Cancel；空取消先插入墓碑，晚到 Try 永远不能再预留。
        return db.queryForMap("SELECT * FROM " + table(order) + " WHERE xid=? AND branch_id=? FOR UPDATE", c.getXid(), c.getBranchId());
    }

    private boolean prepare(BusinessActionContext c, Purchase p, boolean order) {
        JdbcTemplate db = order ? mysql : pg;
        return Boolean.TRUE.equals((order ? mysqlTx : pgTx).execute(status -> {
            var b = lock(db, order, c, "NEW");
            if ("CANCELLED".equals(b.get("state"))) throw new BusinessException("拒绝晚到 Try：分支已取消");
            if (!"NEW".equals(b.get("state"))) {
                if (!p.orderId().equals(b.get("order_id")) || !p.userId().equals(b.get("user_id"))
                        || !p.productId().equals(b.get("product_id")) || p.quantity() != ((Number) b.get("quantity")).intValue()
                        || p.unitPrice().compareTo((BigDecimal) b.get("unit_price")) != 0)
                    throw new BusinessException("重复分支内容冲突");
                return true;
            }
            if (order) {
                // 订单与库存同属 MySQL，一个动作本地事务保证一起预留或一起回滚。
                mysql.update("INSERT INTO seata_tcc_orders(order_id,user_id,product_id,quantity,unit_price,amount,state,xid,branch_id) VALUES (?,?,?,?,?,?,'PREPARED',?,?)",
                        p.orderId(), p.userId(), p.productId(), p.quantity(), p.unitPrice(), p.amount(), c.getXid(), c.getBranchId());
                p.failIf(Purchase.Failure.AFTER_ORDER);
                if (mysql.update("UPDATE seata_tcc_stock SET available=available-?,frozen=frozen+? WHERE product_id=? AND available>=?", p.quantity(), p.quantity(), p.productId(), p.quantity()) != 1)
                    throw new BusinessException("库存不足或商品不存在");
            } else {
                if (pg.update("UPDATE seata_tcc_account SET available=available-?,frozen=frozen+? WHERE user_id=? AND available>=?", p.amount(), p.amount(), p.userId(), p.amount()) != 1)
                    throw new BusinessException("余额不足或用户不存在");
            }
            db.update("UPDATE " + table(order) + " SET state='PREPARED',order_id=?,user_id=?,product_id=?,quantity=?,amount=?,unit_price=? WHERE xid=? AND branch_id=?",
                    p.orderId(), p.userId(), p.productId(), p.quantity(), p.amount(), p.unitPrice(), c.getXid(), c.getBranchId());
            return true;
        }));
    }

    public boolean tryOrder(BusinessActionContext c, Purchase p) {
        return prepare(c, p, true);
    }

    public boolean tryAccount(BusinessActionContext c, Purchase p) {
        return prepare(c, p, false);
    }

    public boolean finish(BusinessActionContext c, boolean order, boolean confirm) {
        JdbcTemplate db = order ? mysql : pg;
        return Boolean.TRUE.equals((order ? mysqlTx : pgTx).execute(tx -> {
            var b = lock(db, order, c, confirm ? "NEW" : "CANCELLED");
            String target = confirm ? "CONFIRMED" : "CANCELLED";
            if (target.equals(b.get("state"))) return true;
            if (!"PREPARED".equals(b.get("state"))) return false;
            if (faults.fail((String) b.get("order_id"), confirm ? "CONFIRM" : "CANCEL")) return false;
            int changed;
            if (order) {
                int q = ((Number) b.get("quantity")).intValue();
                changed = mysql.update("UPDATE seata_tcc_stock SET frozen=frozen-?,available=available+? WHERE product_id=? AND frozen>=?", q, confirm ? 0 : q, b.get("product_id"), q);
                mysql.update("UPDATE seata_tcc_orders SET state=? WHERE order_id=?", target, b.get("order_id"));
            } else {
                var amount = (BigDecimal) b.get("amount");
                changed = pg.update("UPDATE seata_tcc_account SET frozen=frozen-?,available=available+? WHERE user_id=? AND frozen>=?", amount, confirm ? BigDecimal.ZERO : amount, b.get("user_id"), amount);
            }
            if (changed != 1) throw new IllegalStateException("冻结资源丢失，拒绝标记二阶段完成");
            db.update("UPDATE " + table(order) + " SET state=? WHERE xid=? AND branch_id=?", target, c.getXid(), c.getBranchId());
            return true;
        }));
    }

    public Map<String, Object> snapshot() {
        // 跨库查询是两个时点的观察快照，不构成分布式只读事务。
        return Map.of("orders", mysql.queryForList("SELECT * FROM seata_tcc_orders ORDER BY order_id"),
                "stock", mysql.queryForList("SELECT * FROM seata_tcc_stock ORDER BY product_id"),
                "accounts", pg.queryForList("SELECT * FROM seata_tcc_account ORDER BY user_id"),
                "orderBranches", mysql.queryForList("SELECT * FROM seata_tcc_order_branch ORDER BY branch_id"),
                "accountBranches", pg.queryForList("SELECT * FROM seata_tcc_account_branch ORDER BY branch_id"), "atomicSnapshot", false);
    }
}

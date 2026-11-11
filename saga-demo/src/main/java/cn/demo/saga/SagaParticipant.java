package cn.demo.saga;

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
 * 教学 Saga 分支：资源真实扣减与动作流水在同一个本地事务内提交。
 * 协调器负责 STOCK -> ACCOUNT 正向调用、ACCOUNT -> STOCK 逆序补偿。
 */
public class SagaParticipant implements ProtocolParticipant {
    private final JdbcTemplate mysql;
    private final JdbcTemplate pg;
    private final TransactionTemplate mysqlTx;
    private final TransactionTemplate pgTx;
    private final ObjectMapper json;

    public SagaParticipant(JdbcTemplate mysql, JdbcTemplate pg,
                           PlatformTransactionManager mysqlTx, PlatformTransactionManager pgTx,
                           ObjectMapper json) {
        this.mysql = mysql;
        this.pg = pg;
        this.mysqlTx = localTransaction(mysqlTx);
        this.pgTx = localTransaction(pgTx);
        this.json = json;
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        // 分支独立提交，不让调用方外围事务把两库错误地当作一次本地事务。
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Override
    public String protocol() {
        return "SAGA";
    }

    @Override
    public void forward(Resource resource, Purchase purchase) {
        execute(resource, purchase, false);
    }

    @Override
    public void confirm(Resource resource, Purchase purchase) {
        throw new UnsupportedOperationException("Saga 正向动作直接提交，没有 Confirm 阶段");
    }

    @Override
    public void reverse(Resource resource, Purchase purchase) {
        execute(resource, purchase, true);
    }

    private void execute(Resource resource, Purchase purchase, boolean compensate) {
        JdbcTemplate database = database(resource);
        TransactionTemplate tx = resource == Resource.STOCK ? mysqlTx : pgTx;
        String table = table(resource);
        tx.executeWithoutResult(status -> {
            // 唯一键先串行化首次并发调用；PG 不捕获唯一键异常，避免事务进入 aborted 状态。
            String insert = "INSERT INTO " + table + "(tx_id,payload,state) VALUES (?,?,'NEW')";
            database.update(insert + (resource == Resource.STOCK
                    ? " ON DUPLICATE KEY UPDATE tx_id=tx_id"
                    : " ON CONFLICT(tx_id) DO NOTHING"), purchase.orderId(), encode(purchase));
            Action action = database.queryForObject("SELECT payload,state FROM " + table + " WHERE tx_id=? FOR UPDATE",
                    (row, n) -> new Action(decode(row.getString("payload")), row.getString("state")), purchase.orderId());
            if (action == null || !action.purchase().sameBusiness(purchase)) {
                throw new BusinessException("Saga 分支编号已用于不同的业务内容：" + purchase.orderId());
            }

            if (compensate) {
                switch (action.state()) {
                    case "COMPENSATED" -> {
                        return;
                    }
                    case "APPLIED" -> refund(resource, purchase);
                    case "NEW" -> { /* 空补偿不修改资源，仍提交墓碑，阻止迟到正向动作。 */ }
                    default -> throw new IllegalStateException("未知 Saga 分支状态：" + action.state());
                }
                database.update("UPDATE " + table + " SET state='COMPENSATED' WHERE tx_id=?", purchase.orderId());
            } else {
                switch (action.state()) {
                    case "APPLIED" -> {
                        return;
                    }
                    case "COMPENSATED" ->
                            throw new BusinessException("Saga 分支已经补偿，拒绝迟到正向动作：" + purchase.orderId());
                    case "NEW" -> debit(resource, purchase);
                    default -> throw new IllegalStateException("未知 Saga 分支状态：" + action.state());
                }
                database.update("UPDATE " + table + " SET state='APPLIED' WHERE tx_id=?", purchase.orderId());
            }
            // SQL 异常直接上抛并回滚，不把锁超时、连接失败或提交失败误报成成功。
        });
    }

    private void debit(Resource resource, Purchase purchase) {
        int affected = switch (resource) {
            case STOCK -> mysql.update("UPDATE demo_stock SET available=available-? WHERE id=? AND available>=?",
                    purchase.quantity(), "SAGA:" + purchase.productId(), purchase.quantity());
            case ACCOUNT -> pg.update("UPDATE demo_accounts SET balance=balance-? WHERE id=? AND balance>=?",
                    purchase.amount(), "SAGA:" + purchase.userId(), purchase.amount());
        };
        if (affected != 1)
            throw new BusinessException(resource == Resource.STOCK ? "Saga 库存不足或商品不存在" : "Saga 余额不足或账户不存在");
    }

    private void refund(Resource resource, Purchase purchase) {
        int affected = switch (resource) {
            case STOCK -> mysql.update("UPDATE demo_stock SET available=available+? WHERE id=?",
                    purchase.quantity(), "SAGA:" + purchase.productId());
            case ACCOUNT -> pg.update("UPDATE demo_accounts SET balance=balance+? WHERE id=?",
                    purchase.amount(), "SAGA:" + purchase.userId());
        };
        // 资源丢失时保留 APPLIED，供协调器恢复后重试，不能伪造已补偿终态。
        if (affected != 1) throw new BusinessException("Saga 补偿资源不存在：" + resource);
    }

    @Override
    public Map<String, Object> snapshot() {
        return Map.of(
                "stock", mysql.queryForList("SELECT id,available FROM demo_stock WHERE id LIKE 'SAGA:%' ORDER BY id"),
                "accounts", pg.queryForList("SELECT id,balance FROM demo_accounts WHERE id LIKE 'SAGA:%' ORDER BY id"),
                "branches", Map.of(
                        "stock", mysql.queryForList("SELECT tx_id,payload,state FROM saga_stock_actions ORDER BY tx_id"),
                        "accounts", pg.queryForList("SELECT tx_id,payload,state FROM saga_account_actions ORDER BY tx_id")));
    }

    private JdbcTemplate database(Resource resource) {
        return switch (resource) {
            case STOCK -> mysql;
            case ACCOUNT -> pg;
        };
    }

    private String table(Resource resource) {
        return switch (resource) {
            case STOCK -> "saga_stock_actions";
            case ACCOUNT -> "saga_account_actions";
        };
    }

    private String encode(Purchase purchase) {
        try {
            return json.writeValueAsString(purchase);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化 Saga 动作", error);
        }
    }

    private Purchase decode(String payload) {
        try {
            return json.readValue(payload, Purchase.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取 Saga 动作流水", error);
        }
    }

    private record Action(Purchase purchase, String state) {
    }
}

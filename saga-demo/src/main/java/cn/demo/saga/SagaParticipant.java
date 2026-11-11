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
 * 教学 Saga 分支：资源真实扣减与动作流水在同一个本地事务内提交。 协调器负责 STOCK -> ACCOUNT 正向调用、ACCOUNT -> STOCK 逆序补偿。
 *
 * @author liusw144
 */
public class SagaParticipant implements ProtocolParticipant {
    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgJdbcTemplate;
    private final TransactionTemplate mysqlTx;
    private final TransactionTemplate pgTx;
    private final ObjectMapper objectMapper;

    public SagaParticipant(JdbcTemplate mysqlJdbcTemplate, JdbcTemplate pgJdbcTemplate,
            PlatformTransactionManager mysqlTx, PlatformTransactionManager pgTx, ObjectMapper objectMapper) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.mysqlTx = localTransaction(mysqlTx);
        this.pgTx = localTransaction(pgTx);
        this.objectMapper = objectMapper;
    }

    private static TransactionTemplate localTransaction(PlatformTransactionManager transactionManager) {
        var transactionTemplate = new TransactionTemplate(transactionManager);
        // 分支独立提交，不让调用方外围事务把两库错误地当作一次本地事务。
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate;
    }

    /**
     * 返回协调器用于路由的 Saga 协议标识。
     *
     * @return 协议标识
     */
    @Override
    public String protocol() {
        return "SAGA";
    }

    /**
     * 在目标资源的本地事务中幂等扣减资源并提交动作流水。
     *
     * @param resource
     *            目标业务资源
     * @param purchase
     *            下单请求
     */
    @Override
    public void forward(Resource resource, Purchase purchase) {
        execute(resource, purchase, false);
    }

    /**
     * 拒绝 Confirm 调用，因为 Saga 的正向动作已经直接提交。
     *
     * @param resource
     *            目标业务资源
     * @param purchase
     *            下单请求
     * @throws UnsupportedOperationException
     *             Saga 协议不支持此阶段
     */
    @Override
    public void confirm(Resource resource, Purchase purchase) {
        throw new UnsupportedOperationException("Saga 正向动作直接提交，没有 Confirm 阶段");
    }

    /**
     * 幂等补偿已执行动作，空补偿记录墓碑以阻止迟到正向动作。
     *
     * @param resource
     *            目标业务资源
     * @param purchase
     *            下单请求
     */
    @Override
    public void reverse(Resource resource, Purchase purchase) {
        execute(resource, purchase, true);
    }

    private void execute(Resource resource, Purchase purchase, boolean compensate) {
        JdbcTemplate database = database(resource);
        TransactionTemplate transactionTemplate = resource == Resource.STOCK ? mysqlTx : pgTx;
        String table = table(resource);
        transactionTemplate.executeWithoutResult(status -> {
            // 唯一键先串行化首次并发调用；PG 不捕获唯一键异常，避免事务进入 aborted 状态。
            String insertSql = "INSERT INTO " + table + "(tx_id,payload,state) VALUES (?,?,'NEW')";
            database.update(insertSql + (resource == Resource.STOCK
                    ? " ON DUPLICATE KEY UPDATE tx_id=tx_id"
                    : " ON CONFLICT(tx_id) DO NOTHING"), purchase.orderId(), encode(purchase));
            Action action = database.queryForObject("SELECT payload,state FROM " + table + " WHERE tx_id=? FOR UPDATE",
                    (row, rowNumber) -> new Action(decode(row.getString("payload")), row.getString("state")),
                    purchase.orderId());
            if (action == null || !action.purchase().sameBusiness(purchase)) {
                throw new BusinessException("Saga 分支编号已用于不同的业务内容：" + purchase.orderId());
            }

            if (compensate) {
                switch (action.state()) {
                    case "COMPENSATED" -> {
                        return;
                    }
                    case "APPLIED" -> {
                        refund(resource, purchase);
                    }
                    case "NEW" -> {
                        // 空补偿不修改资源，仍提交墓碑，阻止迟到正向动作。
                    }
                    default -> {
                        throw new IllegalStateException("未知 Saga 分支状态：" + action.state());
                    }
                }
                database.update("UPDATE " + table + " SET state='COMPENSATED' WHERE tx_id=?", purchase.orderId());
            } else {
                switch (action.state()) {
                    case "APPLIED" -> {
                        return;
                    }
                    case "COMPENSATED" -> {
                        throw new BusinessException("Saga 分支已经补偿，拒绝迟到正向动作：" + purchase.orderId());
                    }
                    case "NEW" -> {
                        debit(resource, purchase);
                    }
                    default -> {
                        throw new IllegalStateException("未知 Saga 分支状态：" + action.state());
                    }
                }
                database.update("UPDATE " + table + " SET state='APPLIED' WHERE tx_id=?", purchase.orderId());
            }
            // SQL 异常直接上抛并回滚，不把锁超时、连接失败或提交失败误报成成功。
        });
    }

    private void debit(Resource resource, Purchase purchase) {
        int affected;
        switch (resource) {
            case STOCK -> {
                affected = mysqlJdbcTemplate.update(
                        "UPDATE demo_stock SET available=available-? WHERE id=? AND available>=?", purchase.quantity(),
                        "SAGA:" + purchase.productId(), purchase.quantity());
            }
            case ACCOUNT -> {
                affected = pgJdbcTemplate.update("UPDATE demo_accounts SET balance=balance-? WHERE id=? AND balance>=?",
                        purchase.amount(), "SAGA:" + purchase.userId(), purchase.amount());
            }
            default -> {
                throw new IncompatibleClassChangeError("未知 Saga 资源：" + resource);
            }
        }
        if (affected != 1) {
            throw new BusinessException(resource == Resource.STOCK ? "Saga 库存不足或商品不存在" : "Saga 余额不足或账户不存在");
        }
    }

    private void refund(Resource resource, Purchase purchase) {
        int affected;
        switch (resource) {
            case STOCK -> {
                affected = mysqlJdbcTemplate.update("UPDATE demo_stock SET available=available+? WHERE id=?",
                        purchase.quantity(), "SAGA:" + purchase.productId());
            }
            case ACCOUNT -> {
                affected = pgJdbcTemplate.update("UPDATE demo_accounts SET balance=balance+? WHERE id=?",
                        purchase.amount(), "SAGA:" + purchase.userId());
            }
            default -> {
                throw new IncompatibleClassChangeError("未知 Saga 资源：" + resource);
            }
        }
        // 资源丢失时保留 APPLIED，供协调器恢复后重试，不能伪造已补偿终态。
        if (affected != 1) {
            throw new BusinessException("Saga 补偿资源不存在：" + resource);
        }
    }

    /**
     * 查询资源和动作流水，供观察正向执行与补偿的最终结果。
     *
     * @return 两库分别查询得到的业务状态
     */
    @Override
    public Map<String, Object> snapshot() {
        return Map.of("stock",
                mysqlJdbcTemplate.queryForList(
                        "SELECT id,available FROM demo_stock WHERE id LIKE 'SAGA:%' ORDER BY id"),
                "accounts",
                pgJdbcTemplate.queryForList("SELECT id,balance FROM demo_accounts WHERE id LIKE 'SAGA:%' ORDER BY id"),
                "branches",
                Map.of("stock",
                        mysqlJdbcTemplate
                                .queryForList("SELECT tx_id,payload,state FROM saga_stock_actions ORDER BY tx_id"),
                        "accounts", pgJdbcTemplate
                                .queryForList("SELECT tx_id,payload,state FROM saga_account_actions ORDER BY tx_id")));
    }

    private JdbcTemplate database(Resource resource) {
        switch (resource) {
            case STOCK -> {
                return mysqlJdbcTemplate;
            }
            case ACCOUNT -> {
                return pgJdbcTemplate;
            }
            default -> {
                throw new IncompatibleClassChangeError("未知 Saga 资源：" + resource);
            }
        }
    }

    private String table(Resource resource) {
        switch (resource) {
            case STOCK -> {
                return "saga_stock_actions";
            }
            case ACCOUNT -> {
                return "saga_account_actions";
            }
            default -> {
                throw new IncompatibleClassChangeError("未知 Saga 资源：" + resource);
            }
        }
    }

    private String encode(Purchase purchase) {
        try {
            return objectMapper.writeValueAsString(purchase);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化 Saga 动作", error);
        }
    }

    private Purchase decode(String payload) {
        try {
            return objectMapper.readValue(payload, Purchase.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法读取 Saga 动作流水", error);
        }
    }

    /**
     * 锁定动作流水后读取的业务请求和持久化状态。
     *
     * @param purchase
     *            动作关联的原始请求
     * @param state
     *            当前动作状态
     * @author liusw144
     */
    private record Action(Purchase purchase, String state) {
    }
}

package cn.demo.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持久化投递状态机，以短事务领取任务，并用租约令牌隔离迟到的旧工作者。
 *
 * @author liusw144
 */
public class TaskStore {
    /**
     * 表示一次已领取任务及其租约令牌，投递期间不持有源库事务。
     *
     * @author liusw144
     */
    public record Task(String id, String type, Purchase purchase, int attempts, String token) {
    }

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;

    public TaskStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper, RetryPolicy retryPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
    }

    /** 将采购请求编码为持久化事件内容。 */
    public String serialize(Purchase purchase) {
        try {
            return objectMapper.writeValueAsString(purchase);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("事件序列化失败", exception);
        }
    }

    /** 从持久化事件内容还原采购请求。 */
    public Purchase deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, Purchase.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("事件格式损坏", exception);
        }
    }

    /** 必须和业务数据使用同一 mysqlTx；不要在业务提交后才调用此方法。 */
    public void enqueue(String type, Purchase purchase) {
        jdbcTemplate.update("INSERT INTO demo_tasks(id,task_type,payload) VALUES (?,?,?)", purchase.orderId(), type,
                serialize(purchase));
    }

    /** 在短事务中领取一个到期任务，并回收已耗尽次数的过期租约。 */
    public Optional<Task> claim(String type) {
        return transactionTemplate.execute(transactionStatus -> {
            // 最后一次领取后崩溃的任务也必须进入死信，不能无限挂在 PROCESSING。
            jdbcTemplate.update(
                    "UPDATE demo_tasks SET status='DEAD',"
                            + "last_error='最后一次领取后租约过期，需要对账或重放' WHERE task_type=? AND attempts>=? "
                            + "AND (status='PENDING' OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6)))",
                    type, retryPolicy.maxAttempts());
            List<Map<String, Object>> taskRows = jdbcTemplate
                    .queryForList(
                            "SELECT * FROM demo_tasks WHERE task_type=? AND attempts<? "
                                    + "AND ((status='PENDING' AND next_at<=CURRENT_TIMESTAMP(6)) "
                                    + "OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6))) "
                                    + "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                            type, retryPolicy.maxAttempts());
            if (taskRows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> taskRow = taskRows.get(0);
            String taskId = (String) taskRow.get("id");
            String leaseToken = UUID.randomUUID().toString();
            Purchase purchase;
            try {
                purchase = deserialize((String) taskRow.get("payload"));
            } catch (IllegalArgumentException exception) {
                jdbcTemplate.update("UPDATE demo_tasks SET status='DEAD',last_error='事件格式损坏' WHERE id=?", taskId);
                return Optional.empty();
            }
            int attempts = ((Number) taskRow.get("attempts")).intValue() + 1;
            jdbcTemplate.update(
                    "UPDATE demo_tasks SET status='PROCESSING',attempts=?,lease_token=?,"
                            + "lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",
                    attempts, leaseToken, taskId);
            return Optional.of(new Task(taskId, type, purchase, attempts, leaseToken));
        });
    }

    /** 校验租约后将任务标记为成功，无需额外业务写入。 */
    public boolean complete(Task task, String result) {
        return finish(task, result, () -> {
            // 此入口仅完成任务，不执行额外业务动作。
        });
    }

    /** 将本地业务确认与任务完成状态放入同一事务，仅接受当前租约。 */
    public boolean finish(Task task, String result, Runnable localBusiness) {
        return Boolean.TRUE.equals(transactionTemplate.execute(transactionStatus -> {
            List<Map<String, Object>> taskRows = jdbcTemplate.queryForList(
                    "SELECT id FROM demo_tasks WHERE id=? AND status='PROCESSING' AND lease_token=? FOR UPDATE",
                    task.id(), task.token());
            if (taskRows.isEmpty()) {
                return false;
            }
            localBusiness.run();
            jdbcTemplate.update("UPDATE demo_tasks SET status='DONE',last_response=?,last_error=NULL,"
                    + "lease_until=NULL,lease_token=NULL WHERE id=?", result, task.id());
            return true;
        }));
    }

    /** 保存失败摘要并按退避策略重新排期，次数耗尽时进入死信。 */
    public void fail(Task task, Exception exception) {
        String message = exception.getClass().getSimpleName() + ": " + Objects.toString(exception.getMessage(), "投递失败");
        jdbcTemplate.update(
                "UPDATE demo_tasks SET status=?,next_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),"
                        + "last_error=?,lease_until=NULL,lease_token=NULL "
                        + "WHERE id=? AND status='PROCESSING' AND lease_token=?",
                retryPolicy.exhausted(task.attempts()) ? "DEAD" : "PENDING", retryPolicy.delaySeconds(task.attempts()),
                message.substring(0, Math.min(message.length(), MAX_ERROR_MESSAGE_LENGTH)), task.id(), task.token());
    }

    /** 仅重置指定方案的死信任务，后续投递仍使用原事件编号。 */
    public void replay(String type, String id) {
        int affectedRows = jdbcTemplate.update(
                "UPDATE demo_tasks SET status='PENDING',attempts=0,next_at=CURRENT_TIMESTAMP(6),"
                        + "lease_until=NULL,lease_token=NULL " + "WHERE id=? AND task_type=? AND status='DEAD'",
                id, type);
        if (affectedRows != 1) {
            throw new BusinessException("仅能重放本方案的 DEAD 任务");
        }
    }

    /** 查询指定方案最近创建的任务和投递结果。 */
    public List<Map<String, Object>> list(String type) {
        return jdbcTemplate
                .queryForList("SELECT id,task_type,status,attempts,next_at,lease_until,last_error,last_response "
                        + "FROM demo_tasks WHERE task_type=? ORDER BY created_at DESC LIMIT 100", type);
    }
}

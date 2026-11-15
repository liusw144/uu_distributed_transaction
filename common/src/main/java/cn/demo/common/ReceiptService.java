package cn.demo.common;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在同一 PostgreSQL 事务中保存回执与扣款结果，通过唯一键去重并串行化重复请求。
 *
 * @author liusw144
 */
public class ReceiptService {
    private static final String RECEIPT_TYPE_COLUMN = "receipt_type";
    private static final String OUTBOX_TYPE = "OUTBOX";
    private static final String NOTIFY_TYPE = "NOTIFY";
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final TaskStore taskStore;
    private final BusinessRepository businessRepository;

    public ReceiptService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, TaskStore taskStore,
            BusinessRepository businessRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.taskStore = taskStore;
        this.businessRepository = businessRepository;
    }

    /** 幂等处理事件；重复事件必须与首次请求的类型和业务内容一致。 */
    public String receive(String type, Purchase purchase) {
        return transactionTemplate.execute(transactionStatus -> {
            int insertedRows = jdbcTemplate.update(
                    "INSERT INTO demo_receipts(event_id,receipt_type,payload,result) "
                            + "VALUES (?,?,?,'PROCESSING') ON CONFLICT(event_id) DO NOTHING",
                    purchase.orderId(), type, taskStore.serialize(purchase));
            if (insertedRows == 0) {
                Map<String, Object> receiptRow = jdbcTemplate.queryForMap(
                        "SELECT receipt_type,payload,result FROM demo_receipts WHERE event_id=?", purchase.orderId());
                if (!type.equals(receiptRow.get(RECEIPT_TYPE_COLUMN))
                        || !purchase.sameBusiness(taskStore.deserialize((String) receiptRow.get("payload")))) {
                    throw new BusinessException("同一事件编号对应了不同内容，拒绝覆盖原回执");
                }
                return (String) receiptRow.get("result");
            }
            String result;
            if (OUTBOX_TYPE.equals(type)) {
                result = businessRepository.debit(OUTBOX_TYPE, purchase) ? "PAID" : "REJECTED";
            } else if (NOTIFY_TYPE.equals(type)) {
                result = "ACK";
            } else {
                throw new IllegalArgumentException("未知事件类型");
            }
            jdbcTemplate.update("UPDATE demo_receipts SET result=? WHERE event_id=?", result, purchase.orderId());
            return result;
        });
    }

    /** 按事件类型和编号查询回执，缺失时返回原有的未找到标记。 */
    public Map<String, Object> find(String type, String id) {
        List<Map<String, Object>> receiptRows = jdbcTemplate.queryForList(
                "SELECT event_id,receipt_type,result,created_at FROM demo_receipts WHERE event_id=? AND receipt_type=?",
                id, type);
        return receiptRows.isEmpty() ? Map.of("result", "NOT_FOUND") : receiptRows.get(0);
    }
}

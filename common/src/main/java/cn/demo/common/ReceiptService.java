package cn.demo.common;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

/** 消费端回执与扣款处于同一个 PG 事务；唯一键既去重又串行化并发重复请求。 */
public class ReceiptService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final TaskStore tasks;private final BusinessRepository business;
    public ReceiptService(JdbcTemplate jdbc,PlatformTransactionManager tm,TaskStore tasks,BusinessRepository business) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.tasks=tasks;this.business=business;
    }
    public String receive(String type,Purchase p) {
        return tx.execute(s->{
            int inserted=jdbc.update("INSERT INTO demo_receipts(event_id,receipt_type,payload,result) VALUES (?,?,?,'PROCESSING') ON CONFLICT(event_id) DO NOTHING",p.orderId(),type,tasks.serialize(p));
            if(inserted==0) {
                var row=jdbc.queryForMap("SELECT receipt_type,payload,result FROM demo_receipts WHERE event_id=?",p.orderId());
                if(!type.equals(row.get("receipt_type")) || !p.sameBusiness(tasks.deserialize((String)row.get("payload"))))
                    throw new BusinessException("同一事件编号对应了不同内容，拒绝覆盖原回执");
                return (String)row.get("result");
            }
            String result;
            if("OUTBOX".equals(type)) result=business.debit("OUTBOX",p)?"PAID":"REJECTED";
            else if("NOTIFY".equals(type)) result="ACK";
            else throw new IllegalArgumentException("未知事件类型");
            jdbc.update("UPDATE demo_receipts SET result=? WHERE event_id=?",result,p.orderId());
            return result;
        });
    }
    public Map<String,Object> find(String type,String id) {
        var rows=jdbc.queryForList("SELECT event_id,receipt_type,result,created_at FROM demo_receipts WHERE event_id=? AND receipt_type=?",id,type);
        return rows.isEmpty()?Map.of("result","NOT_FOUND"):rows.get(0);
    }
}

package cn.demo.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 持久化投递状态机：短事务领取，网络调用在事务外执行，租约令牌隔离迟到的旧工作者。 */
public class TaskStore {
    public record Task(String id,String type,Purchase purchase,int attempts,String token) {}
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    private final ObjectMapper json; private final RetryPolicy retry;
    public TaskStore(JdbcTemplate jdbc,PlatformTransactionManager tm,ObjectMapper json,RetryPolicy retry) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.json=json;this.retry=retry;
    }
    public String serialize(Purchase p) {
        try { return json.writeValueAsString(p); } catch(JsonProcessingException e) { throw new IllegalArgumentException("事件序列化失败",e); }
    }
    public Purchase deserialize(String s) {
        try { return json.readValue(s,Purchase.class); } catch(JsonProcessingException e) { throw new IllegalArgumentException("事件格式损坏",e); }
    }
    /** 必须和业务数据使用同一 mysqlTx；不要在业务提交后才调用此方法。 */
    public void enqueue(String type,Purchase p) {
        jdbc.update("INSERT INTO demo_tasks(id,task_type,payload) VALUES (?,?,?)",p.orderId(),type,serialize(p));
    }
    public Optional<Task> claim(String type) {
        return tx.execute(s->{
            // 最后一次领取后崩溃的任务也必须进入死信，不能无限挂在 PROCESSING。
            jdbc.update("UPDATE demo_tasks SET status='DEAD',last_error='最后一次领取后租约过期，需要对账或重放' WHERE task_type=? AND attempts>=? AND (status='PENDING' OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6)))",type,retry.maxAttempts());
            var rows=jdbc.queryForList("SELECT * FROM demo_tasks WHERE task_type=? AND attempts<? AND ((status='PENDING' AND next_at<=CURRENT_TIMESTAMP(6)) OR (status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6))) ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",type,retry.maxAttempts());
            if(rows.isEmpty()) return Optional.empty();
            var row=rows.get(0);String id=(String)row.get("id"),token=UUID.randomUUID().toString();
            Purchase purchase;
            try { purchase=deserialize((String)row.get("payload")); }
            catch(IllegalArgumentException e) {
                jdbc.update("UPDATE demo_tasks SET status='DEAD',last_error='事件格式损坏' WHERE id=?",id);
                return Optional.empty();
            }
            int attempts=((Number)row.get("attempts")).intValue()+1;
            jdbc.update("UPDATE demo_tasks SET status='PROCESSING',attempts=?,lease_token=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=?",attempts,token,id);
            return Optional.of(new Task(id,type,purchase,attempts,token));
        });
    }
    public boolean complete(Task task,String result) { return finish(task,result,()->{}); }
    public boolean finish(Task task,String result,Runnable localBusiness) {
        return Boolean.TRUE.equals(tx.execute(s->{
            var rows=jdbc.queryForList("SELECT id FROM demo_tasks WHERE id=? AND status='PROCESSING' AND lease_token=? FOR UPDATE",task.id(),task.token());
            if(rows.isEmpty()) return false;
            localBusiness.run();
            jdbc.update("UPDATE demo_tasks SET status='DONE',last_response=?,last_error=NULL,lease_until=NULL,lease_token=NULL WHERE id=?",result,task.id());
            return true;
        }));
    }
    public void fail(Task task,Exception error) {
        String message=error.getClass().getSimpleName()+": "+Objects.toString(error.getMessage(),"投递失败");
        jdbc.update("UPDATE demo_tasks SET status=?,next_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),last_error=?,lease_until=NULL,lease_token=NULL WHERE id=? AND status='PROCESSING' AND lease_token=?",
                retry.exhausted(task.attempts())?"DEAD":"PENDING",retry.delaySeconds(task.attempts()),message.substring(0,Math.min(message.length(),1000)),task.id(),task.token());
    }
    public void replay(String type,String id) {
        int n=jdbc.update("UPDATE demo_tasks SET status='PENDING',attempts=0,next_at=CURRENT_TIMESTAMP(6),lease_until=NULL,lease_token=NULL WHERE id=? AND task_type=? AND status='DEAD'",id,type);
        if(n!=1) throw new BusinessException("仅能重放本方案的 DEAD 任务");
    }
    public List<Map<String,Object>> list(String type) {
        return jdbc.queryForList("SELECT id,task_type,status,attempts,next_at,lease_until,last_error,last_response FROM demo_tasks WHERE task_type=? ORDER BY created_at DESC LIMIT 100",type);
    }
}

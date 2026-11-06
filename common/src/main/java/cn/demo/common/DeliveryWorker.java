package cn.demo.common;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import java.util.Set;

/** Outbox 与通知共用可靠投递机制，业务确认规则由 type 区分。 */
public class DeliveryWorker {
    public record Ack(String result) {}
    private static final Logger log=LoggerFactory.getLogger(DeliveryWorker.class);
    private final String type,url; private final TaskStore tasks; private final RestTemplate http; private final BusinessRepository business;
    public DeliveryWorker(String type,String url,TaskStore tasks,RestTemplate http,BusinessRepository business) {
        this.type=type;this.url=url;this.tasks=tasks;this.http=http;this.business=business;
    }
    @Scheduled(fixedDelayString="${demo.worker.interval-ms:1000}")
    public void tick() {
        for(int i=0;i<20;i++) {
            var claimed=tasks.claim(type); if(claimed.isEmpty()) return;
            var task=claimed.get();
            try {
                // HTTP 调用不持有源库事务。超时不能证明对方未提交，下一次须使用原事件编号。
                Ack ack=http.postForObject(url,task.purchase(),Ack.class);
                Set<String> allowed="OUTBOX".equals(type)?Set.of("PAID","REJECTED"):Set.of("ACK");
                if(ack==null || !allowed.contains(ack.result())) throw new BusinessException("未收到有效业务 ACK");
                tasks.finish(task,ack.result(),()->{
                    if("OUTBOX".equals(type)) business.finishOutbox(task.purchase(),ack.result());
                });
            } catch(Exception e) {
                tasks.fail(task,e);log.warn("{} 任务 {} 第 {} 次投递失败：{}",type,task.id(),task.attempts(),e.toString());
            }
        }
    }
}

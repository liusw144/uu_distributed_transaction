package cn.demo.common;

import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;

/**
 * 为 Outbox 与通知执行可靠投递，并按事件类型校验业务回执。
 *
 * @author liusw144
 */
public class DeliveryWorker {
    /**
     * 表示消费端返回的业务处理结果。
     *
     * @author liusw144
     */
    public record Ack(String result) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final int MAX_TASKS_PER_TICK = 20;
    private final String type;
    private final String deliveryUrl;
    private final TaskStore taskStore;
    private final RestTemplate restTemplate;
    private final BusinessRepository businessRepository;

    public DeliveryWorker(String type, String deliveryUrl, TaskStore taskStore, RestTemplate restTemplate,
            BusinessRepository businessRepository) {
        this.type = type;
        this.deliveryUrl = deliveryUrl;
        this.taskStore = taskStore;
        this.restTemplate = restTemplate;
        this.businessRepository = businessRepository;
    }

    /** 在单次调度内按批次上限领取和投递任务，失败后记录重试状态。 */
    @Scheduled(fixedDelayString = "${demo.worker.interval-ms:1000}")
    public void tick() {
        for (int taskIndex = 0; taskIndex < MAX_TASKS_PER_TICK; taskIndex++) {
            Optional<TaskStore.Task> claimedTask = taskStore.claim(type);
            if (claimedTask.isEmpty()) {
                return;
            }
            TaskStore.Task task = claimedTask.get();
            try {
                // HTTP 调用不持有源库事务。超时不能证明对方未提交，下一次须使用原事件编号。
                Ack acknowledgment = restTemplate.postForObject(deliveryUrl, task.purchase(), Ack.class);
                Set<String> allowedResults = "OUTBOX".equals(type) ? Set.of("PAID", "REJECTED") : Set.of("ACK");
                if (acknowledgment == null || !allowedResults.contains(acknowledgment.result())) {
                    throw new BusinessException("未收到有效业务 ACK");
                }
                taskStore.finish(task, acknowledgment.result(), () -> {
                    if ("OUTBOX".equals(type)) {
                        businessRepository.finishOutbox(task.purchase(), acknowledgment.result());
                    }
                });
            } catch (Exception exception) {
                taskStore.fail(task, exception);
                LOGGER.warn("{} 任务 {} 第 {} 次投递失败", type, task.id(), task.attempts(), exception);
            }
        }
    }
}

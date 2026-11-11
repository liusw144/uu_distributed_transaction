package cn.demo.common.workflow;

import cn.demo.common.BusinessException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 注入本地二阶段故障；可模拟分支提交后回执丢失，持久化状态不依赖故障计数。
 *
 * @author liusw144
 */
public class WorkflowFaults {
    /**
     * 保存一个阶段剩余的调用失败与回执丢失次数。
     *
     * @author liusw144
     */
    private record Counters(AtomicInteger failures, AtomicInteger lostAcks) {
    }

    private final Map<String, Counters> counters = Map.of("confirm",
            new Counters(new AtomicInteger(), new AtomicInteger()), "cancel",
            new Counters(new AtomicInteger(), new AtomicInteger()), "compensate",
            new Counters(new AtomicInteger(), new AtomicInteger()));

    /** 设置指定二阶段动作的故障次数。 */
    public void configure(String phase, int failNext, int loseAckNext) {
        if (!counters.containsKey(phase) || failNext < 0 || loseAckNext < 0) {
            throw new IllegalArgumentException("故障阶段或计数非法");
        }
        counters.get(phase).failures().set(failNext);
        counters.get(phase).lostAcks().set(loseAckNext);
    }

    private boolean take(AtomicInteger counter) {
        return counter.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0;
    }

    /** 在调用参与者前按计数模拟暂时失败。 */
    public void before(String phase) {
        if (take(counters.get(phase).failures())) {
            throw new BusinessException("模拟二阶段暂时失败：" + phase);
        }
    }

    /** 在参与者提交后按计数模拟回执丢失。 */
    public void after(String phase) {
        if (take(counters.get(phase).lostAcks())) {
            throw new BusinessException("模拟分支已提交但 ACK 丢失：" + phase);
        }
    }
}

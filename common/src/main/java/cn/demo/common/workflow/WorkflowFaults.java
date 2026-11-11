package cn.demo.common.workflow;

import cn.demo.common.BusinessException;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地故障注入。after 表示参与者已提交，但协调器没有收到 ACK；持久化状态不依赖这些计数。
 */
public class WorkflowFaults {
    private record Counters(AtomicInteger failures, AtomicInteger lostAcks) {
    }

    private final Map<String, Counters> counters = Map.of(
            "confirm", new Counters(new AtomicInteger(), new AtomicInteger()),
            "cancel", new Counters(new AtomicInteger(), new AtomicInteger()),
            "compensate", new Counters(new AtomicInteger(), new AtomicInteger()));

    public void configure(String phase, int failNext, int loseAckNext) {
        if (!counters.containsKey(phase) || failNext < 0 || loseAckNext < 0)
            throw new IllegalArgumentException("故障阶段或计数非法");
        counters.get(phase).failures().set(failNext);
        counters.get(phase).lostAcks().set(loseAckNext);
    }

    private boolean take(AtomicInteger n) {
        return n.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
    }

    public void before(String phase) {
        if (take(counters.get(phase).failures())) throw new BusinessException("模拟二阶段暂时失败：" + phase);
    }

    public void after(String phase) {
        if (take(counters.get(phase).lostAcks())) throw new BusinessException("模拟分支已提交但 ACK 丢失：" + phase);
    }
}

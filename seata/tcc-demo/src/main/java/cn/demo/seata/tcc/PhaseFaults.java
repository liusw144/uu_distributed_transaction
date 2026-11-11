package cn.demo.seata.tcc;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PhaseFaults {
    // 仅演示故障计数存内存，真实分支状态始终落在所属数据库。
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    public void arm(String orderId, String phase, int count) {
        if (!phase.matches("CONFIRM|CANCEL") || count < 0 || count > 10) throw new IllegalArgumentException();
        counts.put(orderId + ":" + phase, new AtomicInteger(count));
    }

    public int remaining(String id, String phase) {
        return counts.getOrDefault(id + ":" + phase, new AtomicInteger()).get();
    }

    public boolean fail(String id, String phase) {
        var n = counts.get(id + ":" + phase);
        return n != null && n.getAndUpdate(v -> Math.max(0, v - 1)) > 0;
    }
}

package cn.demo.seata.tcc;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记录 TCC 二阶段演示故障剩余次数。
 *
 * @author liusw144
 */
@Component
public class PhaseFaults {
    private static final int MAX_FAILURE_COUNT = 10;
    private static final String SUPPORTED_PHASE_PATTERN = "CONFIRM|CANCEL";

    /**
     * 仅演示故障计数存内存，真实分支状态始终落在所属数据库。
     */
    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    /**
     * 设置指定订单和阶段的模拟故障次数。
     *
     * @param orderId
     *            业务订单编号
     * @param phase
     *            二阶段名称
     * @param count
     *            模拟故障次数
     */
    public void arm(String orderId, String phase, int count) {
        if (!phase.matches(SUPPORTED_PHASE_PATTERN) || count < 0 || count > MAX_FAILURE_COUNT) {
            throw new IllegalArgumentException("阶段必须为 CONFIRM/CANCEL，故障次数必须在 0 到 " + MAX_FAILURE_COUNT + " 之间");
        }
        counts.put(orderId + ":" + phase, new AtomicInteger(count));
    }

    /**
     * 查询指定订单和阶段的剩余模拟故障次数。
     *
     * @param orderId
     *            业务订单编号
     * @param phase
     *            二阶段名称
     * @return 剩余故障次数
     */
    public int remaining(String orderId, String phase) {
        return counts.getOrDefault(orderId + ":" + phase, new AtomicInteger()).get();
    }

    /**
     * 原子消费一次模拟故障机会。
     *
     * @param orderId
     *            业务订单编号
     * @param phase
     *            二阶段名称
     * @return 当前调用是否应模拟失败
     */
    public boolean fail(String orderId, String phase) {
        AtomicInteger remainingCount = counts.get(orderId + ":" + phase);
        return remainingCount != null && remainingCount.getAndUpdate(currentCount -> Math.max(0, currentCount - 1)) > 0;
    }
}

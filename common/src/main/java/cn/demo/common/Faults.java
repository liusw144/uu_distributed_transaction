package cn.demo.common;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供本地故障演示计数；重启后清零，业务幂等状态仍保存在数据库。
 *
 * @author liusw144
 */
@Component
public class Faults {
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicInteger lostAcknowledgments = new AtomicInteger();

    private boolean take(AtomicInteger counter) {
        return counter.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0;
    }

    /** 在业务执行前按计数模拟消费端不可用。 */
    public void before() {
        if (take(failures)) {
            throw new BusinessException("模拟接收端暂时不可用");
        }
    }

    /** 在业务提交后按计数模拟回执丢失。 */
    public void after() {
        if (take(lostAcknowledgments)) {
            throw new BusinessException("模拟已提交但 ACK 丢失");
        }
    }

    /** 设置后续请求的失败次数与回执丢失次数。 */
    public void set(int failures, int lostAcks) {
        if (failures < 0 || lostAcks < 0) {
            throw new IllegalArgumentException("故障次数和回执丢失次数不能为负数");
        }
        this.failures.set(failures);
        lostAcknowledgments.set(lostAcks);
    }

    /**
     * 提供本地故障配置和健康查询接口。
     *
     * @author liusw144
     */
    @RestController
    public static class Controller {
        private final Faults faults;

        public Controller(Faults faults) {
            this.faults = faults;
        }

        /** 更新本地故障计数并返回当前请求中的配置。 */
        @PostMapping("/demo/faults")
        public Map<String, Object> set(@RequestParam(defaultValue = "0") int failNext,
                @RequestParam(defaultValue = "0") int loseAckNext) {
            faults.set(failNext, loseAckNext);
            return Map.of("failNext", failNext, "loseAckNext", loseAckNext);
        }

        /** 返回进程存活状态。 */
        @GetMapping("/demo/health")
        public Map<String, String> health() {
            return Map.of("status", "UP");
        }
    }
}

package cn.demo.seata.saga;

import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 提供 Saga 下单、实例查询、补偿恢复和故障注入接口。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/seata/saga")
public class SagaController {
    private final SagaService service;
    private final SagaParticipant participant;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param service
     *            业务服务
     * @param participant
     *            Saga 业务参与者
     */
    public SagaController(SagaService service, SagaParticipant participant) {
        this.service = service;
        this.participant = participant;
    }

    /**
     * 提交购买请求，相同业务号返回原状态机实例。
     *
     * @param purchase
     *            购买请求
     * @return 状态机实例信息
     */
    @PostMapping("/orders")
    public Map<String, Object> submit(@Valid @RequestBody Purchase purchase) {
        return service.submit(purchase);
    }

    /**
     * 查询各数据库当前状态；查询结果不保证同一时点。
     *
     * @return 各资源的观察状态
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return participant.snapshot();
    }

    /**
     * 读取状态机实例与持久化节点日志。
     *
     * @param id
     *            状态机实例编号
     * @return 状态机实例信息
     */
    @GetMapping("/instances/{id}")
    public Map<String, Object> instance(@PathVariable String id) {
        return service.instance(id);
    }

    /**
     * 在业务号锁内重新读取实例并重试失败补偿。
     *
     * @param id
     *            状态机实例编号
     * @return 补偿后的状态机实例信息
     */
    @PostMapping("/instances/{id}/compensate")
    public Map<String, Object> compensate(@PathVariable String id) {
        return service.compensate(id);
    }

    /**
     * 设置指定订单资源下一次补偿的故障。
     *
     * @param orderId
     *            业务订单编号
     * @param resource
     *            业务资源名称
     * @param loseAck
     *            是否在补偿提交后模拟响应丢失
     * @return 故障是否已设置
     */
    @PostMapping("/faults/{orderId}/{resource}")
    public Map<String, Object> fail(@PathVariable String orderId, @PathVariable String resource,
            @RequestParam(defaultValue = "false") boolean loseAck) {
        participant.failNext(orderId, resource, loseAck);
        return Map.of("armed", true);
    }
}

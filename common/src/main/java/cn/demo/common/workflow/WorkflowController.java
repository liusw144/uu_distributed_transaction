package cn.demo.common.workflow;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 TCC 与 Saga 订单命令、状态查询和故障配置接口。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/${demo.workflow.protocol}")
@ConditionalOnProperty(name = "demo.workflow.enabled", havingValue = "true")
public class WorkflowController {
    private final WorkflowEngine engine;
    private final WorkflowStore store;
    private final ProtocolParticipant participant;
    private final WorkflowFaults faults;
    private final BusinessRepository businessRepository;

    public WorkflowController(WorkflowEngine engine, WorkflowStore store, ProtocolParticipant participant,
            WorkflowFaults faults, BusinessRepository businessRepository) {
        this.engine = engine;
        this.store = store;
        this.participant = participant;
        this.faults = faults;
        this.businessRepository = businessRepository;
    }

    /** 创建或重试订单，TCC 可选择仅预留资源。 */
    @PostMapping("/orders")
    public WorkflowStore.Flow order(@Valid @RequestBody Purchase purchase,
            @RequestParam(defaultValue = "false") boolean prepareOnly) {
        return engine.start(purchase, prepareOnly);
    }

    /** 查询订单对应的持久化协调状态。 */
    @GetMapping("/orders/{id}")
    public WorkflowStore.Flow order(@PathVariable String id) {
        return engine.get(id);
    }

    /** 持久化 TCC 确认决议并驱动确认动作。 */
    @PostMapping("/orders/{id}/confirm")
    public WorkflowStore.Flow confirm(@PathVariable String id) {
        return engine.confirm(id);
    }

    /** 持久化 TCC 取消决议并驱动撤销动作。 */
    @PostMapping("/orders/{id}/cancel")
    public WorkflowStore.Flow cancel(@PathVariable String id) {
        return engine.cancel(id);
    }

    /** 重置恢复次数并沿原有决议继续执行。 */
    @PostMapping("/orders/{id}/retry")
    public WorkflowStore.Flow retry(@PathVariable String id) {
        return engine.retry(id);
    }

    /** 汇总参与者、订单及协调日志状态。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> view = new LinkedHashMap<>(participant.snapshot());
        view.put("orders", businessRepository.orders(engine.protocol()));
        view.put("workflows", store.list(engine.protocol()));
        return view;
    }

    /** 设置当前协议支持的二阶段故障。 */
    @PostMapping("/faults")
    public Map<String, Object> faults(@RequestParam String phase, @RequestParam(defaultValue = "0") int failNext,
            @RequestParam(defaultValue = "0") int loseAckNext) {
        Set<String> phases = "TCC".equals(engine.protocol()) ? Set.of("confirm", "cancel") : Set.of("compensate");
        if (!phases.contains(phase)) {
            throw new IllegalArgumentException("本协议不支持该故障阶段");
        }
        faults.configure(phase, failNext, loseAckNext);
        return Map.of("phase", phase, "failNext", failNext, "loseAckNext", loseAckNext);
    }
}

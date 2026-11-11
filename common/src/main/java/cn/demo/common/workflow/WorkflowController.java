package cn.demo.common.workflow;

import cn.demo.common.*;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/${demo.workflow.protocol}")
@ConditionalOnProperty(name = "demo.workflow.enabled", havingValue = "true")
public class WorkflowController {
    private final WorkflowEngine engine;
    private final WorkflowStore store;
    private final ProtocolParticipant participant;
    private final WorkflowFaults faults;
    private final BusinessRepository business;

    public WorkflowController(WorkflowEngine engine, WorkflowStore store, ProtocolParticipant participant,
                              WorkflowFaults faults, BusinessRepository business) {
        this.engine = engine;
        this.store = store;
        this.participant = participant;
        this.faults = faults;
        this.business = business;
    }

    @PostMapping("/orders")
    public WorkflowStore.Flow order(@Valid @RequestBody Purchase p,
                                    @RequestParam(defaultValue = "false") boolean prepareOnly) {
        return engine.start(p, prepareOnly);
    }

    @GetMapping("/orders/{id}")
    public WorkflowStore.Flow order(@PathVariable String id) {
        return engine.get(id);
    }

    @PostMapping("/orders/{id}/confirm")
    public WorkflowStore.Flow confirm(@PathVariable String id) {
        return engine.confirm(id);
    }

    @PostMapping("/orders/{id}/cancel")
    public WorkflowStore.Flow cancel(@PathVariable String id) {
        return engine.cancel(id);
    }

    @PostMapping("/orders/{id}/retry")
    public WorkflowStore.Flow retry(@PathVariable String id) {
        return engine.retry(id);
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> view = new LinkedHashMap<>(participant.snapshot());
        view.put("orders", business.orders(engine.protocol()));
        view.put("workflows", store.list(engine.protocol()));
        return view;
    }

    @PostMapping("/faults")
    public Map<String, Object> faults(@RequestParam String phase,
                                      @RequestParam(defaultValue = "0") int failNext, @RequestParam(defaultValue = "0") int loseAckNext) {
        Set<String> phases = "TCC".equals(engine.protocol()) ? Set.of("confirm", "cancel") : Set.of("compensate");
        if (!phases.contains(phase)) throw new IllegalArgumentException("本协议不支持该故障阶段");
        faults.configure(phase, failNext, loseAckNext);
        return Map.of("phase", phase, "failNext", failNext, "loseAckNext", loseAckNext);
    }
}

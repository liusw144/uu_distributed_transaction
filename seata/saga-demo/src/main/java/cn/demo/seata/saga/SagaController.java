package cn.demo.seata.saga;

import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/seata/saga")
public class SagaController {
    private final SagaService service;
    private final SagaParticipant participant;

    public SagaController(SagaService service, SagaParticipant participant) {
        this.service = service;
        this.participant = participant;
    }

    @PostMapping("/orders")
    public Map<String, Object> submit(@Valid @RequestBody Purchase p) {
        return service.submit(p);
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return participant.snapshot();
    }

    @GetMapping("/instances/{id}")
    public Map<String, Object> instance(@PathVariable String id) {
        return service.instance(id);
    }

    @PostMapping("/instances/{id}/compensate")
    public Map<String, Object> compensate(@PathVariable String id) {
        return service.compensate(id);
    }

    @PostMapping("/faults/{orderId}/{resource}")
    public Map<String, Object> fail(@PathVariable String orderId, @PathVariable String resource,
                                    @RequestParam(defaultValue = "false") boolean loseAck) {
        participant.failNext(orderId, resource, loseAck);
        return Map.of("armed", true);
    }
}

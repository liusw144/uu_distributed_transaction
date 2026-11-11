package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/seata/tcc")
public class TccController {
    private final TccService service;
    private final TccStore store;
    private final PhaseFaults faults;

    public TccController(TccService service, TccStore store, PhaseFaults faults) {
        this.service = service;
        this.store = store;
        this.faults = faults;
    }

    @PostMapping("/orders")
    public Object purchase(@Valid @RequestBody Purchase p) {
        return service.purchase(p);
    }

    @GetMapping("/state")
    public Object state() {
        return store.snapshot();
    }

    @PostMapping("/faults/{orderId}/{phase}/{count}")
    public Object fault(@PathVariable String orderId, @PathVariable String phase, @PathVariable int count) {
        faults.arm(orderId, phase, count);
        return Map.of("orderId", orderId, "phase", phase, "remaining", count);
    }
}

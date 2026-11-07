package cn.demo.xa;

import cn.demo.common.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/xa")
public class XaController {
    private final XaService service;
    private final BusinessRepository business;

    public XaController(XaService service, BusinessRepository business) {
        this.service = service;
        this.business = business;
    }

    @PostMapping("/orders")
    public Map<String, String> purchase(@Valid @RequestBody Purchase p) {
        service.purchase(p);
        return Map.of("orderId", p.orderId(), "status", "PAID");
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return business.snapshot("XA");
    }
}

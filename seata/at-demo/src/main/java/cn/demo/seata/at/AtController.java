package cn.demo.seata.at;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/seata/at")
public class AtController {
    private final AtOrderService service;
    private final BusinessRepository business;

    public AtController(AtOrderService service, BusinessRepository business) {
        this.service = service;
        this.business = business;
    }

    @PostMapping("/orders")
    public Map<String, Object> order(@Valid @RequestBody Purchase purchase) {
        return Map.of("orderId", purchase.orderId(), "mode", "AT", "xid", service.purchase(purchase));
    }

    /**
     * 两个查询用于观察结果，不承诺跨数据库的一致快照。
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return business.snapshot("SEATA_AT");
    }
}


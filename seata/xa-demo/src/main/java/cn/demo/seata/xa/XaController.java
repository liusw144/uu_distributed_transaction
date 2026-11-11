package cn.demo.seata.xa;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/seata/xa")
public class XaController {
    private final XaOrderService service;
    private final BusinessRepository business;

    public XaController(XaOrderService service, BusinessRepository business) {
        this.service = service;
        this.business = business;
    }

    @PostMapping("/orders")
    public Map<String, Object> order(@Valid @RequestBody Purchase purchase) {
        return Map.of("orderId", purchase.orderId(), "mode", "XA", "xid", service.purchase(purchase));
    }

    /**
     * 两个查询用于观察结果，不承诺跨数据库的一致快照。
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return business.snapshot("SEATA_XA");
    }
}


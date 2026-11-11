package cn.demo.xa;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 XA 下单入口和跨库业务状态查询。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/xa")
public class XaController {
    private static final String SCENARIO = "XA";

    private final XaService service;
    private final BusinessRepository business;

    public XaController(XaService service, BusinessRepository business) {
        this.service = service;
        this.business = business;
    }

    /**
     * 在 XA 全局事务成功提交后返回已支付订单。
     *
     * @param purchase
     *            下单请求
     * @return 订单编号和支付状态
     */
    @PostMapping("/orders")
    public Map<String, String> purchase(@Valid @RequestBody Purchase purchase) {
        service.purchase(purchase);
        return Map.of("orderId", purchase.orderId(), "status", "PAID");
    }

    /**
     * 查询 XA 场景下的订单、库存和账户状态。
     *
     * @return 业务状态快照
     */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return business.snapshot(SCENARIO);
    }
}

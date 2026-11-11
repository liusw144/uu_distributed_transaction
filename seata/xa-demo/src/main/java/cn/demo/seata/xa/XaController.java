package cn.demo.seata.xa;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提供 XA 下单和跨库状态观察接口。
 *
 * @author liusw144
 */
@RestController
@RequestMapping("/api/seata/xa")
public class XaController {
    private final XaOrderService service;
    private final BusinessRepository business;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param service
     *            业务服务
     * @param business
     *            跨库业务资源仓库
     */
    public XaController(XaOrderService service, BusinessRepository business) {
        this.service = service;
        this.business = business;
    }

    /**
     * 提交订单并返回全局事务标识。
     *
     * @param purchase
     *            购买请求
     * @return 订单编号、事务模式和全局事务标识
     */
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

package cn.demo.at;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 按应用角色注册 AT 订单入口以及库存、账户内部接口。
 *
 * @author liusw144
 */
public final class AtControllers {
    private AtControllers() {
    }

    /**
     * 接收订单请求并提供 AT 业务状态查询。
     *
     * @author liusw144
     */
    @RestController
    @ConditionalOnProperty(name = "demo.role", havingValue = "order", matchIfMissing = true)
    @RequestMapping("/api/at")
    public static class Order {
        private final AtOrderService service;
        private final BusinessRepository business;

        public Order(AtOrderService service, BusinessRepository business) {
            this.service = service;
            this.business = business;
        }

        /**
         * 协调三个 AT 分支完成订单并返回全局事务编号。
         *
         * @param purchase
         *            下单请求
         * @return 订单编号、全局事务编号和支付状态
         */
        @PostMapping("/orders")
        public Map<String, String> purchase(@Valid @RequestBody Purchase purchase) {
            String xid = service.purchase(purchase);
            return Map.of("orderId", purchase.orderId(), "xid", xid, "status", "PAID");
        }

        /**
         * 查询 AT 订单、库存和账户的业务状态。
         *
         * @return 业务状态快照
         */
        @GetMapping("/state")
        public Map<String, Object> state() {
            return business.snapshot("AT");
        }
    }

    /**
     * 提供携带 XID 的库存扣减内部接口。
     *
     * @author liusw144
     */
    @RestController
    @ConditionalOnProperty(name = "demo.role", havingValue = "stock")
    public static class Stock {
        private final AtBranches branches;

        public Stock(AtBranches branches) {
            this.branches = branches;
        }

        /**
         * 执行库存 AT 分支。
         *
         * @param purchase
         *            下单请求
         * @return 分支处理状态
         */
        @PostMapping("/internal/stock")
        public Map<String, String> reduce(@Valid @RequestBody Purchase purchase) {
            branches.stock(purchase);
            return Map.of("status", "OK");
        }
    }

    /**
     * 提供携带 XID 的账户扣款内部接口。
     *
     * @author liusw144
     */
    @RestController
    @ConditionalOnProperty(name = "demo.role", havingValue = "account")
    public static class Account {
        private final AtBranches branches;

        public Account(AtBranches branches) {
            this.branches = branches;
        }

        /**
         * 执行账户 AT 分支。
         *
         * @param purchase
         *            下单请求
         * @return 分支处理状态
         */
        @PostMapping("/internal/account")
        public Map<String, String> debit(@Valid @RequestBody Purchase purchase) {
            branches.account(purchase);
            return Map.of("status", "OK");
        }
    }
}

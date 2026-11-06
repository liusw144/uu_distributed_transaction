package cn.demo.at;
import cn.demo.common.*;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

public final class AtControllers {
    private AtControllers() {}
    @RestController @ConditionalOnProperty(name="demo.role",havingValue="order",matchIfMissing=true)
    @RequestMapping("/api/at") public static class Order {
        private final AtOrderService service;private final BusinessRepository business;
        public Order(AtOrderService service,BusinessRepository business) { this.service=service;this.business=business; }
        @PostMapping("/orders") public Map<String,String> purchase(@Valid @RequestBody Purchase p) { String xid=service.purchase(p);return Map.of("orderId",p.orderId(),"xid",xid,"status","PAID"); }
        @GetMapping("/state") public Map<String,Object> state() { return business.snapshot("AT"); }
    }
    @RestController @ConditionalOnProperty(name="demo.role",havingValue="stock")
    public static class Stock {
        private final AtBranches branches;public Stock(AtBranches branches) { this.branches=branches; }
        @PostMapping("/internal/stock") public Map<String,String> reduce(@Valid @RequestBody Purchase p) { branches.stock(p);return Map.of("status","OK"); }
    }
    @RestController @ConditionalOnProperty(name="demo.role",havingValue="account")
    public static class Account {
        private final AtBranches branches;public Account(AtBranches branches) { this.branches=branches; }
        @PostMapping("/internal/account") public Map<String,String> debit(@Valid @RequestBody Purchase p) { branches.account(p);return Map.of("status","OK"); }
    }
}

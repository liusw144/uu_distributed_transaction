package cn.demo.seata.tcc;

import cn.demo.common.*;
import io.seata.spring.annotation.GlobalTransactional;
import io.seata.core.context.RootContext;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class TccService {
    private final OrderAction order;
    private final AccountAction account;
    private final TccStore store;

    public TccService(OrderAction order, AccountAction account, TccStore store) {
        this.order = order;
        this.account = account;
        this.store = store;
    }

    @GlobalTransactional(name = "seata-tcc-purchase", rollbackFor = Exception.class, timeoutMills = 60000)
    public Map<String, Object> purchase(Purchase p) {
        var existing = store.existing(p);
        if (existing != null) return existing;
        String xid = RootContext.getXID();
        order.prepare(null, p.orderId(), p);
        p.failIf(Purchase.Failure.AFTER_STOCK);
        account.prepare(null, p.orderId(), p);
        p.failIf(Purchase.Failure.AFTER_ACCOUNT);
        // 返回只说明 TM 已请求提交；以查询到两个分支均 CONFIRMED 为最终完成。
        return Map.of("orderId", p.orderId(), "xid", xid, "status", "COMMIT_REQUESTED");
    }
}

package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.stereotype.Component;

@Component
public class OrderActionImpl implements OrderAction {
    private final TccStore store;

    public OrderActionImpl(TccStore store) {
        this.store = store;
    }

    public boolean prepare(BusinessActionContext c, String orderId, Purchase p) {
        return store.tryOrder(c, p);
    }

    public boolean confirm(BusinessActionContext c) {
        return store.finish(c, true, true);
    }

    public boolean cancel(BusinessActionContext c) {
        return store.finish(c, true, false);
    }
}

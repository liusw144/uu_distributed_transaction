package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.stereotype.Component;

@Component
public class AccountActionImpl implements AccountAction {
    private final TccStore store;

    public AccountActionImpl(TccStore store) {
        this.store = store;
    }

    public boolean prepare(BusinessActionContext c, String orderId, Purchase p) {
        return store.tryAccount(c, p);
    }

    public boolean confirm(BusinessActionContext c) {
        return store.finish(c, false, true);
    }

    public boolean cancel(BusinessActionContext c) {
        return store.finish(c, false, false);
    }
}

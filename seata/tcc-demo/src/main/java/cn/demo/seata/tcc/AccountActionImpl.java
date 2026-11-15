package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.stereotype.Component;

/**
 * 将账户 TCC 动作委托给持久化分支存储。
 *
 * @author liusw144
 */
@Component
public class AccountActionImpl implements AccountAction {
    private final TccStore store;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param store
     *            持久化分支存储
     */
    public AccountActionImpl(TccStore store) {
        this.store = store;
    }

    @Override
    public boolean prepare(BusinessActionContext actionContext, String orderId, Purchase purchase) {
        return store.tryAccount(actionContext, purchase);
    }

    @Override
    public boolean confirm(BusinessActionContext actionContext) {
        return store.finish(actionContext, false, true);
    }

    @Override
    public boolean cancel(BusinessActionContext actionContext) {
        return store.finish(actionContext, false, false);
    }
}

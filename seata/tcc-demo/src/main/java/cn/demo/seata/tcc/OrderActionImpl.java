package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.springframework.stereotype.Component;

/**
 * 将订单与库存 TCC 动作委托给持久化分支存储。
 *
 * @author liusw144
 */
@Component
public class OrderActionImpl implements OrderAction {
    private final TccStore store;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param store
     *            持久化分支存储
     */
    public OrderActionImpl(TccStore store) {
        this.store = store;
    }

    @Override
    public boolean prepare(BusinessActionContext actionContext, String orderId, Purchase purchase) {
        return store.tryOrder(actionContext, purchase);
    }

    @Override
    public boolean confirm(BusinessActionContext actionContext) {
        return store.finish(actionContext, true, true);
    }

    @Override
    public boolean cancel(BusinessActionContext actionContext) {
        return store.finish(actionContext, true, false);
    }
}

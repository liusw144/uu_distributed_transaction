package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.*;

@LocalTCC
public interface OrderAction {
    @TwoPhaseBusinessAction(name = "seataTccOrder", commitMethod = "confirm", rollbackMethod = "cancel", useTCCFence = false)
    boolean prepare(BusinessActionContext context, @BusinessActionContextParameter(paramName = "orderId") String orderId, Purchase purchase);

    boolean confirm(BusinessActionContext context);

    boolean cancel(BusinessActionContext context);
}

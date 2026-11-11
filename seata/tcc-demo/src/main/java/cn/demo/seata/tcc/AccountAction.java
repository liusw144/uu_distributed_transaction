package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.*;

@LocalTCC
public interface AccountAction {
    // 禁用默认 Fence，防止 PostgreSQL 分支被默认 MySQL 事务管理器包裹。
    @TwoPhaseBusinessAction(name = "seataTccAccount", commitMethod = "confirm", rollbackMethod = "cancel", useTCCFence = false)
    boolean prepare(BusinessActionContext context, @BusinessActionContextParameter(paramName = "orderId") String orderId, Purchase purchase);

    boolean confirm(BusinessActionContext context);

    boolean cancel(BusinessActionContext context);
}

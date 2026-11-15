package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * 定义账户资源的 TCC 预留、确认和取消动作。
 *
 * @author liusw144
 */
@LocalTCC
public interface AccountAction {
    /**
     * 预留当前 TCC 分支需要的业务资源。
     * <p>
     * 禁用默认 Fence，防止 PostgreSQL 分支被默认 MySQL 事务管理器包裹。
     *
     * @param context
     *            Seata 分支上下文
     * @param orderId
     *            业务订单编号
     * @param purchase
     *            购买请求
     * @return 资源预留是否成功
     */
    @TwoPhaseBusinessAction(name = "seataTccAccount", commitMethod = "confirm", rollbackMethod = "cancel",
            useTCCFence = false)
    boolean prepare(BusinessActionContext context,
            @BusinessActionContextParameter(paramName = "orderId") String orderId, Purchase purchase);

    /**
     * 确认已预留资源，重复确认保持幂等。
     *
     * @param context
     *            Seata 分支上下文
     * @return 分支确认是否完成
     */
    boolean confirm(BusinessActionContext context);

    /**
     * 取消已预留资源，并通过持久化状态防止迟到预留。
     *
     * @param context
     *            Seata 分支上下文
     * @return 分支取消是否完成
     */
    boolean cancel(BusinessActionContext context);
}

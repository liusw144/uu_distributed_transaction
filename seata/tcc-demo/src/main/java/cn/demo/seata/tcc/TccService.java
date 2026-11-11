package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import io.seata.spring.annotation.GlobalTransactional;
import io.seata.core.context.RootContext;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 通过全局事务协调订单和账户 TCC 预留动作。
 *
 * @author liusw144
 */
@Service
public class TccService {
    private final OrderAction order;
    private final AccountAction account;
    private final TccStore store;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param order
     *            订单动作
     * @param account
     *            账户动作
     * @param store
     *            持久化分支存储
     */
    public TccService(OrderAction order, AccountAction account, TccStore store) {
        this.order = order;
        this.account = account;
        this.store = store;
    }

    /**
     * 提交跨库购买请求。
     *
     * @param purchase
     *            购买请求
     * @return 提交结果
     */
    @GlobalTransactional(name = "seata-tcc-purchase", rollbackFor = Exception.class, timeoutMills = 60000)
    public Map<String, Object> purchase(Purchase purchase) {
        Map<String, Object> existingPurchase = store.existing(purchase);
        if (existingPurchase != null) {
            return existingPurchase;
        }
        String xid = RootContext.getXID();
        order.prepare(null, purchase.orderId(), purchase);
        purchase.failIf(Purchase.Failure.AFTER_STOCK);
        account.prepare(null, purchase.orderId(), purchase);
        purchase.failIf(Purchase.Failure.AFTER_ACCOUNT);
        // 返回只说明 TM 已请求提交；以查询到两个分支均 CONFIRMED 为最终完成。
        return Map.of("orderId", purchase.orderId(), "xid", xid, "status", "COMMIT_REQUESTED");
    }
}

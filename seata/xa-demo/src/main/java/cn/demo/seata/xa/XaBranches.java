package cn.demo.seata.xa;

import cn.demo.common.BusinessException;
import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import io.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分支事务与全局入口放在不同 Spring Bean 中，保证本地事务与全局事务的代理都生效。
 *
 * @author liusw144
 */
@Service
public class XaBranches {
    private final BusinessRepository business;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param business
     *            跨库业务资源仓库
     */
    public XaBranches(BusinessRepository business) {
        this.business = business;
    }

    /**
     * 在订单库本地事务中创建订单并扣减库存。
     *
     * @param purchase
     *            购买请求
     */
    @Transactional(transactionManager = "mysqlTx", rollbackFor = Exception.class)
    public void orderAndStock(Purchase purchase) {
        requireGlobalTransaction();
        business.insertOrder("SEATA_XA", purchase, "PAID");
        purchase.failIf(Purchase.Failure.AFTER_ORDER);
        business.reduceStock("SEATA_XA", purchase);
    }

    /**
     * 在账户库本地事务中扣减余额。
     *
     * @param purchase
     *            购买请求
     */
    @Transactional(transactionManager = "pgTx", rollbackFor = Exception.class)
    public void account(Purchase purchase) {
        requireGlobalTransaction();
        business.requireDebit("SEATA_XA", purchase);
    }

    private static void requireGlobalTransaction() {
        if (!RootContext.inGlobalTransaction()) {
            throw new BusinessException("分支必须在 Seata 全局事务中执行");
        }
    }
}

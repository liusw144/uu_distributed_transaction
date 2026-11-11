package cn.demo.seata.xa;

import cn.demo.common.*;
import io.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分支事务与全局入口放在不同 Spring Bean 中，保证本地事务与全局事务的代理都生效。
 */
@Service
public class XaBranches {
    private final BusinessRepository business;

    public XaBranches(BusinessRepository business) {
        this.business = business;
    }

    @Transactional(transactionManager = "mysqlTx", rollbackFor = Exception.class)
    public void orderAndStock(Purchase purchase) {
        requireGlobalTransaction();
        business.insertOrder("SEATA_XA", purchase, "PAID");
        purchase.failIf(Purchase.Failure.AFTER_ORDER);
        business.reduceStock("SEATA_XA", purchase);
    }

    @Transactional(transactionManager = "pgTx", rollbackFor = Exception.class)
    public void account(Purchase purchase) {
        requireGlobalTransaction();
        business.requireDebit("SEATA_XA", purchase);
    }

    private static void requireGlobalTransaction() {
        if (!RootContext.inGlobalTransaction()) throw new BusinessException("分支必须在 Seata 全局事务中执行");
    }
}


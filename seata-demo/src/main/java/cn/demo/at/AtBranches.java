package cn.demo.at;

import cn.demo.common.BusinessException;
import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import io.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 执行 Seata AT 订单、库存和账户的本地事务分支。
 *
 * @author liusw144
 */
@Service
public class AtBranches {
    private static final String SCENARIO = "AT";

    private final BusinessRepository business;

    public AtBranches(BusinessRepository business) {
        this.business = business;
    }

    /**
     * 拒绝未携带全局事务上下文的分支调用。
     */
    public static void requireXid() {
        if (!RootContext.inGlobalTransaction()) {
            throw new BusinessException("分支必须携带 Seata XID");
        }
    }

    /**
     * 在 MySQL 本地事务中创建订单并注册 AT 分支。
     *
     * @param purchase
     *            下单请求
     */
    @Transactional(transactionManager = "mysqlTx", rollbackFor = Exception.class)
    public void order(Purchase purchase) {
        requireXid();
        business.insertOrder(SCENARIO, purchase, "PAID");
    }

    /**
     * 在 MySQL 本地事务中扣减库存并注册 AT 分支。
     *
     * @param purchase
     *            下单请求
     */
    @Transactional(transactionManager = "mysqlTx", rollbackFor = Exception.class)
    public void stock(Purchase purchase) {
        requireXid();
        business.reduceStock(SCENARIO, purchase);
    }

    /**
     * 在 PostgreSQL 本地事务中扣减余额并注册 AT 分支。
     *
     * @param purchase
     *            下单请求
     */
    @Transactional(transactionManager = "pgTx", rollbackFor = Exception.class)
    public void account(Purchase purchase) {
        requireXid();
        business.requireDebit(SCENARIO, purchase);
    }
}

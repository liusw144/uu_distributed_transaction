package cn.demo.xa;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 JTA 全局事务协调订单、库存和账户的 XA 分支。
 *
 * @author liusw144
 */
@Service
public class XaService {
    private static final String SCENARIO = "XA";

    private final BusinessRepository business;

    public XaService(BusinessRepository business) {
        this.business = business;
    }

    /**
     * 依次创建订单、扣减库存和余额；任一步骤失败均由 Atomikos 驱动两库回滚。
     *
     * @param purchase
     *            下单请求及故障注入位置
     */
    @Transactional(rollbackFor = Exception.class, timeout = 30)
    public void purchase(Purchase purchase) {
        business.insertOrder(SCENARIO, purchase, "PAID");
        purchase.failIf(Purchase.Failure.AFTER_ORDER);
        business.reduceStock(SCENARIO, purchase);
        purchase.failIf(Purchase.Failure.AFTER_STOCK);
        business.requireDebit(SCENARIO, purchase);
        purchase.failIf(Purchase.Failure.AFTER_ACCOUNT);
    }
}

package cn.demo.xa;
import cn.demo.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class XaService {
    private final BusinessRepository business;
    public XaService(BusinessRepository business) { this.business=business; }
    /** 一个 JTA 全局事务关联两个 XA 数据源；异常时由 Atomikos 驱动两库回滚。 */
    @Transactional(rollbackFor=Exception.class,timeout=30)
    public void purchase(Purchase p) {
        business.insertOrder("XA",p,"PAID");p.failIf(Purchase.Failure.AFTER_ORDER);
        business.reduceStock("XA",p);p.failIf(Purchase.Failure.AFTER_STOCK);
        business.requireDebit("XA",p);p.failIf(Purchase.Failure.AFTER_ACCOUNT);
    }
}

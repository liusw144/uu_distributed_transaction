package cn.demo.notification;
import cn.demo.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class NotificationService {
    private final BusinessRepository business;private final TaskStore tasks;
    public NotificationService(BusinessRepository business,TaskStore tasks) { this.business=business;this.tasks=tasks; }
    /** 假定支付平台已经确认支付成功；本事务记录该事实及待通知事件，不模拟原子转账。 */
    @Transactional(transactionManager="mysqlTx",rollbackFor=Exception.class)
    public void recordPayment(Purchase p) {
        business.insertOrder("NOTIFY",p,"PAID");tasks.enqueue("NOTIFY",p);
        p.failIf(Purchase.Failure.AFTER_ORDER);
    }
}

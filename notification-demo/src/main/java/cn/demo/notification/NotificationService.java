package cn.demo.notification;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import cn.demo.common.TaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记录支付成功事实，并在同一事务内生成可靠通知任务。
 *
 * @author liusw144
 */
@Service
public class NotificationService {
    private static final String SCENARIO = "NOTIFY";

    private final BusinessRepository business;
    private final TaskStore tasks;

    public NotificationService(BusinessRepository business, TaskStore tasks) {
        this.business = business;
        this.tasks = tasks;
    }

    /**
     * 假定支付平台已确认支付成功，原子记录支付事实及待通知事件。
     *
     * @param purchase
     *            已支付的业务请求及故障注入位置
     */
    @Transactional(transactionManager = "mysqlTx", rollbackFor = Exception.class)
    public void recordPayment(Purchase purchase) {
        business.insertOrder(SCENARIO, purchase, "PAID");
        tasks.enqueue(SCENARIO, purchase);
        purchase.failIf(Purchase.Failure.AFTER_ORDER);
    }
}

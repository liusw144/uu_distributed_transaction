package cn.demo.outbox;

import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import cn.demo.common.TaskStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在同一个 MySQL 事务中提交订单、库存变更和待投递消息。
 *
 * @author liusw144
 */
@Service
public class OutboxService {
    private static final String SCENARIO = "OUTBOX";

    private final BusinessRepository business;
    private final TaskStore tasks;

    public OutboxService(BusinessRepository business, TaskStore tasks) {
        this.business = business;
        this.tasks = tasks;
    }

    /**
     * 提交本地下单事务，账户扣款由消息消费者后续执行。
     *
     * @param purchase
     *            下单请求及故障注入位置
     */
    @Transactional(transactionManager = "mysqlTx", rollbackFor = Exception.class)
    public void purchase(Purchase purchase) {
        business.insertOrder(SCENARIO, purchase, "PENDING");
        purchase.failIf(Purchase.Failure.AFTER_ORDER);
        business.reduceStock(SCENARIO, purchase);
        purchase.failIf(Purchase.Failure.AFTER_STOCK);
        tasks.enqueue(SCENARIO, purchase);
        // AFTER_ACCOUNT 在本方案表示本地提交前失败，PG 扣款由后续消费者执行。
        purchase.failIf(Purchase.Failure.AFTER_ACCOUNT);
    }
}

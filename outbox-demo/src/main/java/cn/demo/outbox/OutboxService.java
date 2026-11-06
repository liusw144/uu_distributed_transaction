package cn.demo.outbox;
import cn.demo.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class OutboxService {
    private final BusinessRepository business;private final TaskStore tasks;
    public OutboxService(BusinessRepository business,TaskStore tasks) { this.business=business;this.tasks=tasks; }
    @Transactional(transactionManager="mysqlTx",rollbackFor=Exception.class)
    public void purchase(Purchase p) {
        business.insertOrder("OUTBOX",p,"PENDING");p.failIf(Purchase.Failure.AFTER_ORDER);
        business.reduceStock("OUTBOX",p);p.failIf(Purchase.Failure.AFTER_STOCK);
        tasks.enqueue("OUTBOX",p);
        // AFTER_ACCOUNT 在本方案表示本地提交前失败，PG 扣款由后续消费者执行。
        p.failIf(Purchase.Failure.AFTER_ACCOUNT);
    }
}

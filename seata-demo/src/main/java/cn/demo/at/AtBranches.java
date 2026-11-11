package cn.demo.at;
import cn.demo.common.*;
import io.seata.core.context.RootContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AtBranches {
    private final BusinessRepository business;
    public AtBranches(BusinessRepository business) { this.business=business; }
    public static void requireXid() { if(!RootContext.inGlobalTransaction()) throw new BusinessException("分支必须携带 Seata XID"); }
    @Transactional(transactionManager="mysqlTx",rollbackFor=Exception.class)
    public void order(Purchase p) { requireXid();business.insertOrder("AT",p,"PAID"); }
    @Transactional(transactionManager="mysqlTx",rollbackFor=Exception.class)
    public void stock(Purchase p) { requireXid();business.reduceStock("AT",p); }
    @Transactional(transactionManager="pgTx",rollbackFor=Exception.class)
    public void account(Purchase p) { requireXid();business.requireDebit("AT",p); }
}

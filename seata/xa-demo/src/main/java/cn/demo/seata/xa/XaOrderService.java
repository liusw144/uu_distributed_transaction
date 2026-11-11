package cn.demo.seata.xa;

import cn.demo.common.Purchase;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

@Service
public class XaOrderService {
    private final XaBranches branches;

    public XaOrderService(XaBranches branches) {
        this.branches = branches;
    }

    @GlobalTransactional(name = "seata-xa-purchase", rollbackFor = Exception.class, timeoutMills = 60000)
    public String purchase(Purchase purchase) {
        String xid = RootContext.getXID();
        branches.orderAndStock(purchase);
        // 此时 MySQL 已完成第一阶段：XA prepare，数据库资源仍被占用。
        purchase.failIf(Purchase.Failure.AFTER_STOCK);
        branches.account(purchase);
        // 账户分支也完成第一阶段后再抛异常，验证两个数据库的二阶段回滚。
        purchase.failIf(Purchase.Failure.AFTER_ACCOUNT);
        return xid;
    }
}


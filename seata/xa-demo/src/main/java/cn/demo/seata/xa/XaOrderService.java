package cn.demo.seata.xa;

import cn.demo.common.Purchase;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

/**
 * 通过全局事务协调 XA 订单、库存和账户分支。
 *
 * @author liusw144
 */
@Service
public class XaOrderService {
    private final XaBranches branches;

    /**
     * 注入业务依赖并初始化当前组件。
     *
     * @param branches
     *            独立事务分支服务
     */
    public XaOrderService(XaBranches branches) {
        this.branches = branches;
    }

    /**
     * 提交跨库购买请求。
     *
     * @param purchase
     *            购买请求
     * @return 提交结果
     */
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

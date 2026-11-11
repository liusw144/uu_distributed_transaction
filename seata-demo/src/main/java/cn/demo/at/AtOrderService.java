package cn.demo.at;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * 开启 Seata AT 全局事务，按订单、库存、账户顺序调用业务分支。
 *
 * @author liusw144
 */
@Service
public class AtOrderService {
    private final AtBranches branches;
    private final RestTemplate http;
    private final String stockUrl;
    private final String accountUrl;

    public AtOrderService(AtBranches branches, RestTemplate http, @Value("${demo.stock-url}") String stockUrl,
            @Value("${demo.account-url}") String accountUrl) {
        this.branches = branches;
        this.http = http;
        this.stockUrl = stockUrl;
        this.accountUrl = accountUrl;
    }

    /**
     * 执行跨服务下单；任一分支或故障注入失败均触发全局回滚。
     *
     * @param purchase
     *            下单请求及故障注入位置
     * @return 本次全局事务编号
     */
    @GlobalTransactional(name = "purchase-at", rollbackFor = Exception.class, timeoutMills = 60000)
    public String purchase(Purchase purchase) {
        String xid = RootContext.getXID();
        branches.order(purchase);
        purchase.failIf(Purchase.Failure.AFTER_ORDER);
        call(stockUrl, purchase, xid);
        purchase.failIf(Purchase.Failure.AFTER_STOCK);
        call(accountUrl, purchase, xid);
        purchase.failIf(Purchase.Failure.AFTER_ACCOUNT);
        return xid;
    }

    private void call(String url, Purchase purchase, String xid) {
        var headers = new HttpHeaders();
        headers.set(RootContext.KEY_XID, xid);
        // 独立服务无法共享 ThreadLocal，必须显式通过 HTTP 传播全局事务编号。
        var response = http.postForEntity(url, new HttpEntity<>(purchase, headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new BusinessException("AT 远程分支失败，地址：" + url + "，状态码：" + response.getStatusCode());
        }
    }
}

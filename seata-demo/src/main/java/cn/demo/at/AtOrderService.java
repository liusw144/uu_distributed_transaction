package cn.demo.at;
import cn.demo.common.*;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

@Service
public class AtOrderService {
    private final AtBranches branches;private final RestTemplate http;private final String stockUrl,accountUrl;
    public AtOrderService(AtBranches branches,RestTemplate http,@Value("${demo.stock-url}") String stockUrl,@Value("${demo.account-url}") String accountUrl) {
        this.branches=branches;this.http=http;this.stockUrl=stockUrl;this.accountUrl=accountUrl;
    }
    @GlobalTransactional(name="purchase-at",rollbackFor=Exception.class,timeoutMills=60000)
    public String purchase(Purchase p) {
        String xid=RootContext.getXID();
        branches.order(p);p.failIf(Purchase.Failure.AFTER_ORDER);
        call(stockUrl,p,xid);p.failIf(Purchase.Failure.AFTER_STOCK);
        call(accountUrl,p,xid);p.failIf(Purchase.Failure.AFTER_ACCOUNT);
        return xid;
    }
    private void call(String url,Purchase p,String xid) {
        var headers=new HttpHeaders();headers.set(RootContext.KEY_XID,xid);
        // 独立服务无法共享 ThreadLocal，必须显式通过 HTTP 传播全局事务编号。
        var response=http.postForEntity(url,new HttpEntity<>(p,headers),String.class);
        if(!response.getStatusCode().is2xxSuccessful()) throw new BusinessException("AT 远程分支失败");
    }
}

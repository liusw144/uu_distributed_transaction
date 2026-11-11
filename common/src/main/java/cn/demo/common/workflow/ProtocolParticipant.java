package cn.demo.common.workflow;

import cn.demo.common.Purchase;

import java.util.Map;

/**
 * 每个动作在资源所在数据库的本地事务中实现；重复请求只能产生一次业务效果。
 */
public interface ProtocolParticipant {
    enum Resource {STOCK, ACCOUNT}

    String protocol();

    void forward(Resource resource, Purchase purchase);

    void confirm(Resource resource, Purchase purchase);

    void reverse(Resource resource, Purchase purchase);

    Map<String, Object> snapshot();
}

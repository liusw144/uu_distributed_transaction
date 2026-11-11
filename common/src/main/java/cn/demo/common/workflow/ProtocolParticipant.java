package cn.demo.common.workflow;

import cn.demo.common.Purchase;
import java.util.Map;

/**
 * 定义事务协议的资源操作，每个动作在资源本地事务中执行，重复请求仅产生一次业务效果。
 *
 * @author liusw144
 */
public interface ProtocolParticipant {
    /**
     * 标识参与全局事务的库存与账户资源。
     *
     * @author liusw144
     */
    enum Resource {
        /** 库存资源。 */
        STOCK,
        /** 账户资源。 */
        ACCOUNT
    }

    /**
     * 返回参与者支持的事务协议。
     *
     * @return 协议名称，例如 TCC 或 SAGA
     */
    String protocol();

    /**
     * 执行资源预留或正向业务动作。
     *
     * @param resource
     *            本次操作的库存或账户资源
     * @param purchase
     *            原始采购请求，重试时必须复用业务编号与业务内容
     */
    void forward(Resource resource, Purchase purchase);

    /**
     * 确认已预留的资源。
     *
     * @param resource
     *            本次确认的库存或账户资源
     * @param purchase
     *            预留阶段使用的原始采购请求
     */
    void confirm(Resource resource, Purchase purchase);

    /**
     * 取消资源预留或补偿正向业务动作。
     *
     * @param resource
     *            本次撤销的库存或账户资源
     * @param purchase
     *            正向阶段使用的原始采购请求
     */
    void reverse(Resource resource, Purchase purchase);

    /**
     * 查询参与者的业务和分支状态。
     *
     * @return 参与者实现提供的业务与分支状态快照
     */
    Map<String, Object> snapshot();
}

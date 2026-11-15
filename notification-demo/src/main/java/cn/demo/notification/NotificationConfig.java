package cn.demo.notification;

import cn.demo.common.BusinessRepository;
import cn.demo.common.DeliveryWorker;
import cn.demo.common.TaskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 配置可靠通知的异步回调投递器。
 *
 * @author liusw144
 */
@Configuration
public class NotificationConfig {
    /**
     * 开启后台任务时创建投递器，由投递器处理持久化任务与重试。
     *
     * @param targetUrl
     *            接收业务事件的目标地址
     * @param tasks
     *            任务存储
     * @param http
     *            HTTP 调用入口
     * @param business
     *            业务数据访问入口
     * @return 当前场景的投递器
     */
    @Bean
    @ConditionalOnProperty(name = "demo.worker.enabled", havingValue = "true", matchIfMissing = true)
    public DeliveryWorker worker(@Value("${demo.callback-url}") String targetUrl, TaskStore tasks, RestTemplate http,
            BusinessRepository business) {
        return new DeliveryWorker("NOTIFY", targetUrl, tasks, http, business);
    }
}

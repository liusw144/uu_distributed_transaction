package cn.demo.notification;
import cn.demo.common.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestTemplate;
@Configuration
public class NotificationConfig {
    @Bean @ConditionalOnProperty(name="demo.worker.enabled",havingValue="true",matchIfMissing=true)
    public DeliveryWorker worker(@Value("${demo.callback-url}") String url,TaskStore tasks,RestTemplate http,BusinessRepository business) {
        return new DeliveryWorker("NOTIFY",url,tasks,http,business);
    }
}

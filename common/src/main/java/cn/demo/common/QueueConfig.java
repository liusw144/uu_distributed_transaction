package cn.demo.common;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="demo.queue.enabled",havingValue="true")
public class QueueConfig {
    @Bean public TaskStore taskStore(@Qualifier("mysqlJdbc") JdbcTemplate jdbc,@Qualifier("mysqlTx") PlatformTransactionManager tm,ObjectMapper json,
            @Value("${demo.retry.max-attempts:8}") int max,@Value("${demo.retry.base-seconds:1}") long base,@Value("${demo.retry.max-seconds:60}") long cap) {
        return new TaskStore(jdbc,tm,json,new RetryPolicy(max,base,cap));
    }
    @Bean public ReceiptService receiptService(@Qualifier("pgJdbc") JdbcTemplate jdbc,@Qualifier("pgTx") PlatformTransactionManager tm,TaskStore tasks,BusinessRepository business) {
        return new ReceiptService(jdbc,tm,tasks,business);
    }
}

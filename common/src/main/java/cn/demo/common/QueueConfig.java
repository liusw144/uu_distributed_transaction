package cn.demo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 按开关注册可靠投递存储、重试策略和消费回执服务。
 *
 * @author liusw144
 */
@Configuration
@ConditionalOnProperty(name = "demo.queue.enabled", havingValue = "true")
public class QueueConfig {
    @Bean
    public TaskStore taskStore(@Qualifier("mysqlJdbc") JdbcTemplate jdbcTemplate,
            @Qualifier("mysqlTx") PlatformTransactionManager transactionManager, ObjectMapper objectMapper,
            @Value("${demo.retry.max-attempts:8}") int maxAttempts,
            @Value("${demo.retry.base-seconds:1}") long baseSeconds,
            @Value("${demo.retry.max-seconds:60}") long maxSeconds) {
        return new TaskStore(jdbcTemplate, transactionManager, objectMapper,
                new RetryPolicy(maxAttempts, baseSeconds, maxSeconds));
    }

    @Bean
    public ReceiptService receiptService(@Qualifier("pgJdbc") JdbcTemplate jdbcTemplate,
            @Qualifier("pgTx") PlatformTransactionManager transactionManager, TaskStore taskStore,
            BusinessRepository businessRepository) {
        return new ReceiptService(jdbcTemplate, transactionManager, taskStore, businessRepository);
    }
}

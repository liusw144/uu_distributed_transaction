package cn.demo.common.workflow;

import cn.demo.common.BusinessRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 按开关注册手写协调器、故障注入器和定时恢复任务。
 *
 * @author liusw144
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "demo.workflow.enabled", havingValue = "true")
public class WorkflowConfig {
    @Bean
    public WorkflowStore workflowStore(@Qualifier("mysqlJdbc") JdbcTemplate jdbcTemplate,
            @Qualifier("mysqlTx") PlatformTransactionManager transactionManager,
            @Qualifier("mysqlDataSource") DataSource dataSource, ObjectMapper objectMapper,
            BusinessRepository businessRepository) {
        return new WorkflowStore(jdbcTemplate, transactionManager, dataSource, objectMapper, businessRepository);
    }

    @Bean
    public WorkflowFaults workflowFaults() {
        return new WorkflowFaults();
    }

    @Bean
    public WorkflowEngine workflowEngine(WorkflowStore store, ProtocolParticipant participant, WorkflowFaults faults,
            @Value("${demo.workflow.max-attempts:8}") int maxAttempts,
            @Value("${demo.workflow.prepare-timeout-seconds:60}") int prepareTimeoutSeconds,
            @Value("${demo.workflow.protocol}") String protocol) {
        if (!protocol.equalsIgnoreCase(participant.protocol())) {
            throw new IllegalArgumentException("URL 协议与参与者不一致");
        }
        return new WorkflowEngine(store, participant, faults, maxAttempts, prepareTimeoutSeconds);
    }

    @Bean
    @ConditionalOnProperty(name = "demo.workflow.recovery-enabled", havingValue = "true", matchIfMissing = true)
    public Recovery workflowRecovery(WorkflowEngine engine) {
        return new Recovery(engine);
    }

    /**
     * 定时驱动未完成的协调事务沿已持久化的决议恢复。
     *
     * @author liusw144
     */
    public static class Recovery {
        private final WorkflowEngine engine;

        public Recovery(WorkflowEngine engine) {
            this.engine = engine;
        }

        /** 触发一轮到期协调事务恢复。 */
        @Scheduled(fixedDelayString = "${demo.workflow.interval-ms:1000}")
        public void tick() {
            engine.recover();
        }
    }
}

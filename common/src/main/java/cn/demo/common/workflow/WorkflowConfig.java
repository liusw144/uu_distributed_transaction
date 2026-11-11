package cn.demo.common.workflow;

import cn.demo.common.BusinessRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "demo.workflow.enabled", havingValue = "true")
public class WorkflowConfig {
    @Bean
    public WorkflowStore workflowStore(@Qualifier("mysqlJdbc") JdbcTemplate jdbc,
                                       @Qualifier("mysqlTx") PlatformTransactionManager tm, @Qualifier("mysqlDataSource") DataSource ds,
                                       ObjectMapper json, BusinessRepository business) {
        return new WorkflowStore(jdbc, tm, ds, json, business);
    }

    @Bean
    public WorkflowFaults workflowFaults() {
        return new WorkflowFaults();
    }

    @Bean
    public WorkflowEngine workflowEngine(WorkflowStore store, ProtocolParticipant participant, WorkflowFaults faults,
                                         @Value("${demo.workflow.max-attempts:8}") int max, @Value("${demo.workflow.prepare-timeout-seconds:60}") int ttl,
                                         @Value("${demo.workflow.protocol}") String protocol) {
        if (!protocol.equalsIgnoreCase(participant.protocol()))
            throw new IllegalArgumentException("URL 协议与参与者不一致");
        return new WorkflowEngine(store, participant, faults, max, ttl);
    }

    @Bean
    @ConditionalOnProperty(name = "demo.workflow.recovery-enabled", havingValue = "true", matchIfMissing = true)
    public Recovery workflowRecovery(WorkflowEngine engine) {
        return new Recovery(engine);
    }

    public static class Recovery {
        private final WorkflowEngine engine;

        public Recovery(WorkflowEngine engine) {
            this.engine = engine;
        }

        @Scheduled(fixedDelayString = "${demo.workflow.interval-ms:1000}")
        public void tick() {
            engine.recover();
        }
    }
}

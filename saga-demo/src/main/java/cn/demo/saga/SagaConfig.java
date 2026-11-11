package cn.demo.saga;

import cn.demo.common.workflow.ProtocolParticipant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 为 SAGA 分支装配两库访问入口和独立的本地事务管理器。
 *
 * @author liusw144
 */
@Configuration
public class SagaConfig {
    /**
     * 创建持久化业务参与者，由协调器驱动其协议阶段。
     *
     * @param mysqlJdbcTemplate
     *            MySQL 访问入口
     * @param pgJdbcTemplate
     *            PostgreSQL 访问入口
     * @param mysqlTx
     *            MySQL 本地事务管理器
     * @param pgTx
     *            PostgreSQL 本地事务管理器
     * @param objectMapper
     *            业务请求序列化器
     * @return 当前协议的业务参与者
     */
    @Bean
    public ProtocolParticipant sagaParticipant(@Qualifier("mysqlJdbc") JdbcTemplate mysqlJdbcTemplate,
            @Qualifier("pgJdbc") JdbcTemplate pgJdbcTemplate, @Qualifier("mysqlTx") PlatformTransactionManager mysqlTx,
            @Qualifier("pgTx") PlatformTransactionManager pgTx, ObjectMapper objectMapper) {
        return new SagaParticipant(mysqlJdbcTemplate, pgJdbcTemplate, mysqlTx, pgTx, objectMapper);
    }
}

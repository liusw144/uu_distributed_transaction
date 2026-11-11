package cn.demo.saga;

import cn.demo.common.workflow.ProtocolParticipant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class SagaConfig {
    @Bean
    public ProtocolParticipant sagaParticipant(@Qualifier("mysqlJdbc") JdbcTemplate mysql,
                                               @Qualifier("pgJdbc") JdbcTemplate pg,
                                               @Qualifier("mysqlTx") PlatformTransactionManager mysqlTx,
                                               @Qualifier("pgTx") PlatformTransactionManager pgTx,
                                               ObjectMapper json) {
        return new SagaParticipant(mysql, pg, mysqlTx, pgTx, json);
    }
}

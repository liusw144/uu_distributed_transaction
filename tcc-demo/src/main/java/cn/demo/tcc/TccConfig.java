package cn.demo.tcc;

import cn.demo.common.workflow.ProtocolParticipant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TccConfig {
    @Bean
    public ProtocolParticipant tccParticipant(@Qualifier("mysqlJdbc") JdbcTemplate mysql,
                                              @Qualifier("pgJdbc") JdbcTemplate pg, @Qualifier("mysqlTx") PlatformTransactionManager mysqlTx,
                                              @Qualifier("pgTx") PlatformTransactionManager pgTx, ObjectMapper json) {
        return new TccParticipant(mysql, pg, mysqlTx, pgTx, json);
    }
}

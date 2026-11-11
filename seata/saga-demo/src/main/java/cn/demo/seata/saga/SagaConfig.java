package cn.demo.seata.saga;

import cn.demo.common.DatabaseFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class SagaConfig {
    @Bean(destroyMethod = "close")
    HikariDataSource sagaMysql(Environment e) {
        var ds = DatabaseFactory.pool(e, true);
        new ResourceDatabasePopulator(new ClassPathResource("sql/mysql.sql")).execute(ds);
        return ds;
    }

    @Bean(destroyMethod = "close")
    HikariDataSource sagaPg(Environment e) {
        var ds = DatabaseFactory.pool(e, false);
        new ResourceDatabasePopulator(new ClassPathResource("sql/postgresql.sql")).execute(ds);
        return ds;
    }

    @Bean
    SagaParticipant sagaParticipant(@Qualifier("sagaMysql") HikariDataSource mysql,
                                    @Qualifier("sagaPg") HikariDataSource pg) {
        return new SagaParticipant(mysql, pg);
    }
}

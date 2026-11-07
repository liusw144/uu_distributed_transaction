package cn.demo.common;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Configuration
@ConditionalOnProperty(name = "demo.datasource.mode", havingValue = "local", matchIfMissing = true)
public class LocalDataSourceConfig {
    @Bean
    @Primary
    public DataSource mysqlDataSource(Environment e) {
        return DatabaseFactory.pool(e, true);
    }

    @Bean
    public DataSource pgDataSource(Environment e) {
        return DatabaseFactory.pool(e, false);
    }

    @Bean
    public DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}

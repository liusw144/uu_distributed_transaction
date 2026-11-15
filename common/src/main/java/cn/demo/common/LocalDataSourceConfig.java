package cn.demo.common;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 在本地事务模式下注册两类数据源及各自的事务管理器。
 *
 * @author liusw144
 */
@Configuration
@ConditionalOnProperty(name = "demo.datasource.mode", havingValue = "local", matchIfMissing = true)
public class LocalDataSourceConfig {
    @Bean
    @Primary
    public DataSource mysqlDataSource(Environment environment) {
        return DatabaseFactory.pool(environment, true);
    }

    @Bean
    public DataSource pgDataSource(Environment environment) {
        return DatabaseFactory.pool(environment, false);
    }

    @Bean
    public DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

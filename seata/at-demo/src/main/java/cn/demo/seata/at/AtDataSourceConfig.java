package cn.demo.seata.at;

import cn.demo.common.DatabaseFactory;
import com.zaxxer.hikari.HikariDataSource;
import io.seata.rm.datasource.DataSourceProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;


@Configuration
public class AtDataSourceConfig {
    @Bean(destroyMethod = "close")
    HikariDataSource rawMysql(Environment env) {
        return DatabaseFactory.pool(env, true);
    }

    @Bean(destroyMethod = "close")
    HikariDataSource rawPg(Environment env) {
        return DatabaseFactory.pool(env, false);
    }

    // 关闭 starter 自动代理，显式代理两个数据库；业务 JdbcTemplate 只能使用代理数据源。
    // AT 在本地提交时写 Undo Log，后续由 TC 决定提交清理或反向恢复。
    @Bean
    @Primary
    DataSource mysqlDataSource(@Qualifier("rawMysql") DataSource raw) {
        return new DataSourceProxy(raw);
    }

    @Bean
    DataSource pgDataSource(@Qualifier("rawPg") DataSource raw) {
        return new DataSourceProxy(raw);
    }

    @Bean
    @Primary
    DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}


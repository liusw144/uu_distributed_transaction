package cn.demo.seata.xa;

import com.zaxxer.hikari.HikariDataSource;
import io.seata.rm.datasource.xa.DataSourceProxyXA;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class XaDataSourceConfig {
    @Bean(destroyMethod = "close")
    @ConfigurationProperties(prefix = "demo.datasource.mysql")
    HikariDataSource rawMysql() {
        // 连接信息由 application.yml 绑定，直接运行主类也能使用本机数据库。
        return new HikariDataSource();
    }

    @Bean(destroyMethod = "close")
    @ConfigurationProperties(prefix = "demo.datasource.postgresql")
    HikariDataSource rawPg() {
        return new HikariDataSource();
    }

    // 关闭 starter 自动代理，显式代理两个数据库；业务 JdbcTemplate 只能使用代理数据源。
    // XA 在本地事务结束时执行 prepare，最终 commit/rollback 由 Seata TC 下发。
    @Bean
    @Primary
    DataSource mysqlDataSource(@Qualifier("rawMysql") DataSource raw) {
        return new DataSourceProxyXA(raw);
    }

    @Bean
    DataSource pgDataSource(@Qualifier("rawPg") DataSource raw) {
        return new DataSourceProxyXA(raw);
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

    @Bean
    InitializingBean checkPostgresXa(@Qualifier("rawPg") DataSource pg) {
        return () -> {
            int prepared = Integer.parseInt(new JdbcTemplate(pg).queryForObject("SHOW max_prepared_transactions", String.class));
            if (prepared <= 0)
                throw new IllegalStateException("PostgreSQL XA 需要 max_prepared_transactions > 0，请按模块 README 配置后重启数据库");
        };
    }
}


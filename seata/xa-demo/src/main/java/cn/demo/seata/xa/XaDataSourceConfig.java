package cn.demo.seata.xa;

import com.zaxxer.hikari.HikariDataSource;
import io.seata.rm.datasource.xa.DataSourceProxyXA;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 配置 XA 代理数据源并检查 PostgreSQL 预备事务支持。
 *
 * @author liusw144
 */
@Configuration
public class XaDataSourceConfig {
    /**
     * 创建 MySQL 原始连接池。
     *
     * @return MySQL 连接池
     */
    @Bean(destroyMethod = "close")
    @ConfigurationProperties(prefix = "demo.datasource.mysql")
    HikariDataSource rawMysql() {
        // 连接信息由 application.yml 绑定，直接运行主类也能使用本机数据库。
        return new HikariDataSource();
    }

    /**
     * 创建 PostgreSQL 原始连接池。
     *
     * @return PostgreSQL 连接池
     */
    @Bean(destroyMethod = "close")
    @ConfigurationProperties(prefix = "demo.datasource.postgresql")
    HikariDataSource rawPg() {
        return new HikariDataSource();
    }

    /**
     * 创建 MySQL 业务数据源。
     * <p>
     * 关闭 starter 自动代理，显式代理两个数据库；业务 JdbcTemplate 只能使用代理数据源。 XA 在本地事务结束时执行 prepare，最终 commit/rollback 由 Seata TC 下发。
     *
     * @param rawDataSource
     *            原始数据源
     * @return MySQL 数据源
     */
    @Bean
    @Primary
    DataSource mysqlDataSource(@Qualifier("rawMysql") DataSource rawDataSource) {
        return new DataSourceProxyXA(rawDataSource);
    }

    /**
     * 创建 PostgreSQL 业务数据源。
     *
     * @param rawDataSource
     *            原始数据源
     * @return PostgreSQL 数据源
     */
    @Bean
    DataSource pgDataSource(@Qualifier("rawPg") DataSource rawDataSource) {
        return new DataSourceProxyXA(rawDataSource);
    }

    /**
     * 创建 MySQL 本地事务管理器。
     *
     * @param dataSource
     *            业务数据源
     * @return MySQL 本地事务管理器
     */
    @Bean
    @Primary
    DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 创建 PostgreSQL 本地事务管理器。
     *
     * @param dataSource
     *            业务数据源
     * @return PostgreSQL 本地事务管理器
     */
    @Bean
    DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 检查 PostgreSQL 是否允许预备事务。
     *
     * @param postgresDataSource
     *            PostgreSQL 原始数据源
     * @return 启动阶段的 XA 配置检查
     */
    @Bean
    InitializingBean checkPostgresXa(@Qualifier("rawPg") DataSource postgresDataSource) {
        return () -> {
            int preparedTransactionLimit = Integer.parseInt(new JdbcTemplate(postgresDataSource)
                    .queryForObject("SHOW max_prepared_transactions", String.class));
            if (preparedTransactionLimit <= 0) {
                throw new IllegalStateException("PostgreSQL XA 需要 max_prepared_transactions > 0，请按模块 README 配置后重启数据库");
            }
        };
    }
}

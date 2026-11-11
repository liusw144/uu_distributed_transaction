package cn.demo.seata.at;

import cn.demo.common.DatabaseFactory;
import com.zaxxer.hikari.HikariDataSource;
import io.seata.rm.datasource.DataSourceProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 配置 AT 原始连接池、代理数据源和本地事务管理器。
 *
 * @author liusw144
 */
@Configuration
public class AtDataSourceConfig {
    /**
     * 创建 MySQL 原始连接池。
     *
     * @param environment
     *            数据源配置环境
     * @return MySQL 连接池
     */
    @Bean(destroyMethod = "close")
    HikariDataSource rawMysql(Environment environment) {
        return DatabaseFactory.pool(environment, true);
    }

    /**
     * 创建 PostgreSQL 原始连接池。
     *
     * @param environment
     *            数据源配置环境
     * @return PostgreSQL 连接池
     */
    @Bean(destroyMethod = "close")
    HikariDataSource rawPg(Environment environment) {
        return DatabaseFactory.pool(environment, false);
    }

    /**
     * 创建 MySQL 业务数据源。
     * <p>
     * 关闭 starter 自动代理，显式代理两个数据库；业务 JdbcTemplate 只能使用代理数据源。 AT 在本地提交时写 Undo Log，后续由 TC 决定提交清理或反向恢复。
     *
     * @param rawDataSource
     *            原始数据源
     * @return MySQL 数据源
     */
    @Bean
    @Primary
    DataSource mysqlDataSource(@Qualifier("rawMysql") DataSource rawDataSource) {
        return new DataSourceProxy(rawDataSource);
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
        return new DataSourceProxy(rawDataSource);
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
}

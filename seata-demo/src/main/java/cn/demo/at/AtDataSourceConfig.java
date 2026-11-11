package cn.demo.at;

import cn.demo.common.DatabaseFactory;
import com.zaxxer.hikari.HikariDataSource;
import io.seata.rm.datasource.DataSourceProxy;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 显式创建 Seata 代理数据源，防止重复代理并保留两库各自的事务管理器。
 *
 * @author liusw144
 */
@Configuration
public class AtDataSourceConfig {
    /**
     * 创建 MySQL 原始连接池。
     *
     * @param environment
     *            数据库连接配置
     * @return 原始 MySQL 数据源
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource rawMysql(Environment environment) {
        return DatabaseFactory.pool(environment, true);
    }

    /**
     * 创建 PostgreSQL 原始连接池。
     *
     * @param environment
     *            数据库连接配置
     * @return 原始 PostgreSQL 数据源
     */
    @Bean(destroyMethod = "close")
    public HikariDataSource rawPg(Environment environment) {
        return DatabaseFactory.pool(environment, false);
    }

    /**
     * 包装 MySQL 数据源，使业务 SQL 参与 Seata AT。
     *
     * @param raw
     *            原始 MySQL 数据源
     * @return MySQL 的 AT 代理数据源
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource(@Qualifier("rawMysql") DataSource raw) {
        return new DataSourceProxy(raw);
    }

    /**
     * 包装 PostgreSQL 数据源，使业务 SQL 参与 Seata AT。
     *
     * @param raw
     *            原始 PostgreSQL 数据源
     * @return PostgreSQL 的 AT 代理数据源
     */
    @Bean
    public DataSource pgDataSource(@Qualifier("rawPg") DataSource raw) {
        return new DataSourceProxy(raw);
    }

    /**
     * 提供默认事务管理器，订单和库存分支使用此管理器。
     *
     * @param dataSource
     *            MySQL 的 AT 代理数据源
     * @return MySQL 本地事务管理器
     */
    @Bean
    @Primary
    public DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 提供账户分支显式选择的事务管理器。
     *
     * @param dataSource
     *            PostgreSQL 的 AT 代理数据源
     * @return PostgreSQL 本地事务管理器
     */
    @Bean
    public DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

package cn.demo.seata.tcc;

import cn.demo.common.DatabaseFactory;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 配置 TCC 动作所属数据库及其独立本地事务管理器。
 *
 * @author liusw144
 */
@Configuration
public class TccDataSources {
    /**
     * 创建 MySQL 业务数据源。
     * <p>
     * TCC 使用原始连接池；两个动作各自只提交所属数据库的本地事务。
     *
     * @param environment
     *            数据源配置环境
     * @return MySQL 数据源
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource(Environment environment) {
        return DatabaseFactory.pool(environment, true);
    }

    /**
     * 创建 PostgreSQL 业务数据源。
     *
     * @param environment
     *            数据源配置环境
     * @return PostgreSQL 数据源
     */
    @Bean
    public DataSource pgDataSource(Environment environment) {
        return DatabaseFactory.pool(environment, false);
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
    public DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource dataSource) {
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
    public DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}

package cn.demo.seata.tcc;

import cn.demo.common.DatabaseFactory;

import javax.sql.DataSource;

import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Configuration
public class TccDataSources {
    // TCC 使用原始连接池；两个动作各自只提交所属数据库的本地事务。
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
    @Primary
    public DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }
}

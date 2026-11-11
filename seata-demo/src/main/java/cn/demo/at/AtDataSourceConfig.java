package cn.demo.at;
import cn.demo.common.DatabaseFactory;
import io.seata.rm.datasource.DataSourceProxy;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

@Configuration
public class AtDataSourceConfig {
    @Bean(destroyMethod="close") public HikariDataSource rawMysql(Environment e) { return DatabaseFactory.pool(e,true); }
    @Bean(destroyMethod="close") public HikariDataSource rawPg(Environment e) { return DatabaseFactory.pool(e,false); }
    // 显式包装每个数据源，禁用 starter 自动代理，避免代理叠加。业务只能注入下面两个代理。
    @Bean @Primary public DataSource mysqlDataSource(@Qualifier("rawMysql") DataSource raw) { return new DataSourceProxy(raw); }
    @Bean public DataSource pgDataSource(@Qualifier("rawPg") DataSource raw) { return new DataSourceProxy(raw); }
    // starter 的通用事务基础设施需要一个默认管理器；账户分支仍显式选择 pgTx。
    @Bean @Primary public DataSourceTransactionManager mysqlTx(@Qualifier("mysqlDataSource") DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean public DataSourceTransactionManager pgTx(@Qualifier("pgDataSource") DataSource ds) { return new DataSourceTransactionManager(ds); }
}

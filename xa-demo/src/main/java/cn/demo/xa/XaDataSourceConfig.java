package cn.demo.xa;

import cn.demo.common.DatabaseFactory;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.mysql.cj.jdbc.MysqlXADataSource;
import org.postgresql.xa.PGXADataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 注册 Atomikos 管理的 XA 数据源并检查 PostgreSQL 预备事务配置。
 *
 * @author liusw144
 */
@Configuration
public class XaDataSourceConfig {
    private static final int MIN_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 8;
    private static final String PREPARED_TRANSACTION_LIMIT_SQL = "SHOW max_prepared_transactions";

    /**
     * 创建订单和库存所在的 MySQL XA 数据源。
     *
     * @param environment
     *            数据库连接配置
     * @return Atomikos 管理的数据源
     * @throws Exception
     *             数据源初始化配置失败时抛出
     */
    @Bean(initMethod = "init", destroyMethod = "close")
    @Primary
    public AtomikosDataSourceBean mysqlDataSource(Environment environment) throws Exception {
        var xaDataSource = new MysqlXADataSource();
        xaDataSource.setUrl(DatabaseFactory.url(environment, true));
        xaDataSource.setUser(DatabaseFactory.user(environment, true));
        xaDataSource.setPassword(DatabaseFactory.password(environment, true));
        var dataSource = new AtomikosDataSourceBean();
        dataSource.setUniqueResourceName("dtx-mysql-8");
        dataSource.setXaDataSource(xaDataSource);
        dataSource.setMinPoolSize(MIN_POOL_SIZE);
        dataSource.setMaxPoolSize(MAX_POOL_SIZE);
        return dataSource;
    }

    /**
     * 创建账户所在的 PostgreSQL XA 数据源。
     *
     * @param environment
     *            数据库连接配置
     * @return Atomikos 管理的数据源
     */
    @Bean(initMethod = "init", destroyMethod = "close")
    public AtomikosDataSourceBean pgDataSource(Environment environment) {
        var xaDataSource = new PGXADataSource();
        xaDataSource.setUrl(DatabaseFactory.url(environment, false));
        xaDataSource.setUser(DatabaseFactory.user(environment, false));
        xaDataSource.setPassword(DatabaseFactory.password(environment, false));
        var dataSource = new AtomikosDataSourceBean();
        dataSource.setUniqueResourceName("dtx-postgresql");
        dataSource.setXaDataSource(xaDataSource);
        dataSource.setMinPoolSize(MIN_POOL_SIZE);
        dataSource.setMaxPoolSize(MAX_POOL_SIZE);
        return dataSource;
    }

    /**
     * 启动时检查 PostgreSQL 是否允许预备事务，避免运行到两阶段提交时才失败。
     *
     * @param pgJdbcTemplate
     *            PostgreSQL 查询入口
     * @return 启动前置检查
     */
    @Bean
    public InitializingBean xaPrerequisite(@Qualifier("pgJdbc") JdbcTemplate pgJdbcTemplate) {
        return () -> {
            if (Integer.parseInt(pgJdbcTemplate.queryForObject(PREPARED_TRANSACTION_LIMIT_SQL, String.class)) < 1) {
                throw new IllegalStateException(
                        "XA 需要 PostgreSQL max_prepared_transactions > 0。请按 README 配置并重启 PG；应用不会自动修改现有数据库配置。");
            }
        };
    }
}

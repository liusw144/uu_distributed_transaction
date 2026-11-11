package cn.demo.common;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 提供各示例共用的连接配置，优先读取 YAML 并兼容直接 JDBC 调用。
 *
 * @author liusw144
 */
public final class DatabaseFactory {
    private static final int MAXIMUM_POOL_SIZE = 8;
    private static final int MINIMUM_IDLE_CONNECTIONS = 1;
    private static final long CONNECTION_TIMEOUT_MILLIS = 5000L;

    private DatabaseFactory() {
    }

    private static String prefix(boolean mysql) {
        return "demo.datasource." + (mysql ? "mysql" : "postgresql");
    }

    /** 按数据库类型解析连接地址，配置缺失时回退到环境变量和本地默认值。 */
    public static String url(Environment environment, boolean mysql) {
        return environment.getProperty(prefix(mysql) + ".jdbc-url",
                environment.getProperty(mysql ? "MYSQL_URL" : "PG_URL",
                        mysql
                                ? "jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql"
                                        + "?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
                                : "jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg"));
    }

    /** 解析数据库用户名，配置缺失时回退到对应环境变量。 */
    public static String user(Environment environment, boolean mysql) {
        return environment.getProperty(prefix(mysql) + ".username",
                environment.getProperty(mysql ? "MYSQL_USER" : "PG_USER", mysql ? "root" : "postgres"));
    }

    /** 解析数据库密码，未配置时保留空密码默认值。 */
    public static String password(Environment environment, boolean mysql) {
        return environment.getProperty(prefix(mysql) + ".password",
                environment.getProperty(mysql ? "MYSQL_PASSWORD" : "PG_PASSWORD", ""));
    }

    /** 创建连接池并在返回前完成 Spring 属性绑定。 */
    public static HikariDataSource pool(Environment environment, boolean mysql) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url(environment, mysql));
        dataSource.setUsername(user(environment, mysql));
        dataSource.setPassword(password(environment, mysql));
        dataSource.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        dataSource.setMinimumIdle(MINIMUM_IDLE_CONNECTIONS);
        dataSource.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
        dataSource.setPoolName(mysql ? "mysql-pool" : "postgres-pool");
        // 在返回前完成绑定，Saga 等模块会在创建 Bean 的过程中立即执行建表 SQL。
        Binder.get(environment).bind(prefix(mysql), Bindable.ofInstance(dataSource));
        return dataSource;
    }
}

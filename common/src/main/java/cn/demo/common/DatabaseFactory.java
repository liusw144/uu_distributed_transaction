package cn.demo.common;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * 各 Demo 共用的连接配置入口：先读取 YAML，并兼容无 Spring 配置的直接 JDBC 调用。
 */
public final class DatabaseFactory {
    private DatabaseFactory() {
    }

    private static String prefix(boolean mysql) {
        return "demo.datasource." + (mysql ? "mysql" : "postgresql");
    }
    public static String url(Environment e, boolean mysql) {
        return e.getProperty(prefix(mysql) + ".jdbc-url", e.getProperty(mysql ? "MYSQL_URL" : "PG_URL", mysql
                ?"jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
                : "jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg"));
    }

    public static String user(Environment e, boolean mysql) {
        return e.getProperty(prefix(mysql) + ".username", e.getProperty(mysql ? "MYSQL_USER" : "PG_USER", mysql ? "root" : "postgres"));
    }

    public static String password(Environment e, boolean mysql) {
        return e.getProperty(prefix(mysql) + ".password", e.getProperty(mysql ? "MYSQL_PASSWORD" : "PG_PASSWORD", ""));
    }
    public static HikariDataSource pool(Environment e, boolean mysql) {
        var ds=new HikariDataSource();
        ds.setJdbcUrl(url(e,mysql)); ds.setUsername(user(e,mysql)); ds.setPassword(password(e,mysql));
        ds.setMaximumPoolSize(8); ds.setMinimumIdle(1); ds.setConnectionTimeout(5000);
        ds.setPoolName(mysql ? "mysql-pool" : "postgres-pool");
        // 在返回前完成绑定，Saga 等模块会在创建 Bean 的过程中立即执行建表 SQL。
        Binder.get(e).bind(prefix(mysql), Bindable.ofInstance(ds));
        return ds;
    }
}

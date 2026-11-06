package cn.demo.common;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.core.env.Environment;
public final class DatabaseFactory {
    private DatabaseFactory() {}
    public static String url(Environment e, boolean mysql) {
        return e.getProperty(mysql?"MYSQL_URL":"PG_URL",mysql
                ?"jdbc:mysql://127.0.0.1:3308/dtx_demo_mysql?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
                :"jdbc:postgresql://127.0.0.1:5432/dtx_demo_pg");
    }
    public static String user(Environment e, boolean mysql) { return e.getProperty(mysql?"MYSQL_USER":"PG_USER",mysql?"root":"postgres"); }
    public static String password(Environment e, boolean mysql) { return e.getProperty(mysql?"MYSQL_PASSWORD":"PG_PASSWORD",""); }
    public static HikariDataSource pool(Environment e, boolean mysql) {
        var ds=new HikariDataSource();
        ds.setJdbcUrl(url(e,mysql)); ds.setUsername(user(e,mysql)); ds.setPassword(password(e,mysql));
        ds.setMaximumPoolSize(8); ds.setMinimumIdle(1); ds.setConnectionTimeout(5000);
        ds.setPoolName(mysql?"mysql-pool":"postgres-pool"); return ds;
    }
}

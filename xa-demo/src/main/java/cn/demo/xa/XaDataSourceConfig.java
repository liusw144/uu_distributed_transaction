package cn.demo.xa;
import cn.demo.common.DatabaseFactory;
import com.atomikos.jdbc.AtomikosDataSourceBean;
import com.mysql.cj.jdbc.MysqlXADataSource;
import org.postgresql.xa.PGXADataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class XaDataSourceConfig {
    @Bean(initMethod="init",destroyMethod="close") @Primary
    public AtomikosDataSourceBean mysqlDataSource(Environment e) throws Exception {
        var xa=new MysqlXADataSource();xa.setUrl(DatabaseFactory.url(e,true));
        xa.setUser(DatabaseFactory.user(e,true));xa.setPassword(DatabaseFactory.password(e,true));
        var ds=new AtomikosDataSourceBean();ds.setUniqueResourceName("dtx-mysql-8");ds.setXaDataSource(xa);
        ds.setMinPoolSize(1);ds.setMaxPoolSize(8);return ds;
    }
    @Bean(initMethod="init",destroyMethod="close")
    public AtomikosDataSourceBean pgDataSource(Environment e) {
        var xa=new PGXADataSource();xa.setUrl(DatabaseFactory.url(e,false));
        xa.setUser(DatabaseFactory.user(e,false));xa.setPassword(DatabaseFactory.password(e,false));
        var ds=new AtomikosDataSourceBean();ds.setUniqueResourceName("dtx-postgresql");ds.setXaDataSource(xa);
        ds.setMinPoolSize(1);ds.setMaxPoolSize(8);return ds;
    }
    @Bean public InitializingBean xaPrerequisite(@Qualifier("pgJdbc") JdbcTemplate pg) {
        return ()->{
            if(Integer.parseInt(pg.queryForObject("SHOW max_prepared_transactions",String.class))<1)
                throw new IllegalStateException("XA 需要 PostgreSQL max_prepared_transactions > 0。请按 README 配置并重启 PG；应用不会自动修改现有数据库配置。");
        };
    }
}

package cn.demo.common;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Configuration
public class CommonConfig {
    @Bean public JdbcTemplate mysqlJdbc(@Qualifier("mysqlDataSource") DataSource ds) { return new JdbcTemplate(ds); }
    @Bean public JdbcTemplate pgJdbc(@Qualifier("pgDataSource") DataSource ds) { return new JdbcTemplate(ds); }
    @Bean public BusinessRepository businessRepository(@Qualifier("mysqlJdbc") JdbcTemplate m,@Qualifier("pgJdbc") JdbcTemplate p) { return new BusinessRepository(m,p); }
    @Bean public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofSeconds(2)).setReadTimeout(Duration.ofSeconds(4)).build();
    }
}

package cn.demo.common;

import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * 注册各事务示例共用的数据库访问与 HTTP 客户端组件。
 *
 * @author liusw144
 */
@Configuration
public class CommonConfig {
    private static final int CONNECT_TIMEOUT_SECONDS = 2;
    private static final int READ_TIMEOUT_SECONDS = 4;

    @Bean
    public JdbcTemplate mysqlJdbc(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTemplate pgJdbc(@Qualifier("pgDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public BusinessRepository businessRepository(@Qualifier("mysqlJdbc") JdbcTemplate mysqlJdbcTemplate,
            @Qualifier("pgJdbc") JdbcTemplate postgresqlJdbcTemplate) {
        return new BusinessRepository(mysqlJdbcTemplate, postgresqlJdbcTemplate);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.setConnectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .setReadTimeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS)).build();
    }
}

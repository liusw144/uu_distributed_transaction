package cn.demo.seata.saga;

import cn.demo.common.DatabaseFactory;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 配置 Saga 数据源和持久化业务参与者。
 *
 * @author liusw144
 */
@Configuration
public class SagaConfig {
    /**
     * 创建并幂等初始化 Saga 的 MySQL 数据源。
     *
     * @param environment
     *            数据源配置环境
     * @return Saga MySQL 连接池
     */
    @Bean(destroyMethod = "close")
    HikariDataSource sagaMysql(Environment environment) {
        HikariDataSource dataSource = DatabaseFactory.pool(environment, true);
        new ResourceDatabasePopulator(new ClassPathResource("sql/mysql.sql")).execute(dataSource);
        return dataSource;
    }

    /**
     * 创建并幂等初始化 Saga 的 PostgreSQL 数据源。
     *
     * @param environment
     *            数据源配置环境
     * @return Saga PostgreSQL 连接池
     */
    @Bean(destroyMethod = "close")
    HikariDataSource sagaPg(Environment environment) {
        HikariDataSource dataSource = DatabaseFactory.pool(environment, false);
        new ResourceDatabasePopulator(new ClassPathResource("sql/postgresql.sql")).execute(dataSource);
        return dataSource;
    }

    /**
     * 创建使用两个独立数据库的 Saga 业务参与者。
     *
     * @param mysqlDataSource
     *            MySQL 数据源
     * @param postgresDataSource
     *            PostgreSQL 原始数据源
     * @return Saga 业务参与者
     */
    @Bean
    SagaParticipant sagaParticipant(@Qualifier("sagaMysql") HikariDataSource mysqlDataSource,
            @Qualifier("sagaPg") HikariDataSource postgresDataSource) {
        return new SagaParticipant(mysqlDataSource, postgresDataSource);
    }
}

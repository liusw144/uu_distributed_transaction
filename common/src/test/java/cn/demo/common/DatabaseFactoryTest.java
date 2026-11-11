package cn.demo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 不建立网络连接，验证 YAML 配置被使用，并保留原有程序化环境变量入口。
 *
 * @author liusw144
 */
class DatabaseFactoryTest {

    @Test
    void mysqlUsesYamlConnectionAndPoolOptions() {
        var env = new MockEnvironment()
                .withProperty("demo.datasource.mysql.jdbc-url", "jdbc:mysql://127.0.0.1:3308/yaml_mysql")
                .withProperty("demo.datasource.mysql.username", "yaml_user")
                .withProperty("demo.datasource.mysql.password", "yaml_password")
                .withProperty("demo.datasource.mysql.maximum-pool-size", "3")
                .withProperty("demo.datasource.mysql.minimum-idle", "2")
                .withProperty("demo.datasource.mysql.connection-timeout", "3000")
                .withProperty("demo.datasource.mysql.pool-name", "yaml-mysql");
        assertEquals("jdbc:mysql://127.0.0.1:3308/yaml_mysql", DatabaseFactory.url(env, true));
        assertEquals("yaml_user", DatabaseFactory.user(env, true));
        assertEquals("yaml_password", DatabaseFactory.password(env, true));
        try (var pool = DatabaseFactory.pool(env, true)) {
            assertEquals("jdbc:mysql://127.0.0.1:3308/yaml_mysql", pool.getJdbcUrl());
            assertEquals("yaml_password", pool.getPassword());
            assertEquals(3, pool.getMaximumPoolSize());
            assertEquals(2, pool.getMinimumIdle());
            assertEquals(3000, pool.getConnectionTimeout());
            assertEquals("yaml-mysql", pool.getPoolName());
        }
    }

    @Test
    void postgresUsesItsOwnYamlConfiguration() {
        var env = new MockEnvironment()
                .withProperty("demo.datasource.postgresql.jdbc-url", "jdbc:postgresql://127.0.0.1:5432/yaml_pg")
                .withProperty("demo.datasource.postgresql.username", "pg_user")
                .withProperty("demo.datasource.postgresql.password", "pg_password");
        assertEquals("jdbc:postgresql://127.0.0.1:5432/yaml_pg", DatabaseFactory.url(env, false));
        assertEquals("pg_user", DatabaseFactory.user(env, false));
        assertEquals("pg_password", DatabaseFactory.password(env, false));
    }

    @Test
    void explicitYamlWinsOverLegacyEnvironmentKeys() {
        var env = new MockEnvironment().withProperty("MYSQL_PASSWORD", "legacy")
                .withProperty("demo.datasource.mysql.password", "yaml");
        assertEquals("yaml", DatabaseFactory.password(env, true));
    }

    @Test
    void yamlPlaceholdersAllowOptionalEnvironmentOverride() {
        var env = new MockEnvironment().withProperty("MYSQL_PASSWORD", "override")
                .withProperty("demo.datasource.mysql.password", "${MYSQL_PASSWORD:local}");
        assertEquals("override", DatabaseFactory.password(env, true));
    }

    @Test
    void callersWithoutYamlRetainLegacyKeysAndEmptyPasswordDefault() {
        var env = new MockEnvironment().withProperty("MYSQL_URL", "jdbc:mysql://127.0.0.1/legacy")
                .withProperty("MYSQL_USER", "legacy_user").withProperty("MYSQL_PASSWORD", "legacy_password")
                .withProperty("PG_PASSWORD", "legacy_pg");
        assertEquals("jdbc:mysql://127.0.0.1/legacy", DatabaseFactory.url(env, true));
        assertEquals("legacy_user", DatabaseFactory.user(env, true));
        assertEquals("legacy_password", DatabaseFactory.password(env, true));
        assertEquals("legacy_pg", DatabaseFactory.password(env, false));
        assertEquals("", DatabaseFactory.password(new MockEnvironment(), true));
        assertEquals("", DatabaseFactory.password(new MockEnvironment(), false));
    }
}

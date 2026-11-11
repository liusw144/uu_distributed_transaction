package cn.demo.at;

import io.seata.rm.datasource.undo.postgresql.PostgresqlUndoLogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.*;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import static org.junit.jupiter.api.Assertions.*;

/** 使用实际 Seata 适配器写入 Undo Log，防止建表脚本与运行时 SQL 不兼容。 */
@EnabledIfEnvironmentVariable(named="DEMO_DB_TESTS",matches="true")
@SpringBootTest(classes=PostgresUndoLogSchemaTest.TestApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="seata.enabled=false")
class PostgresUndoLogSchemaTest {
    @SpringBootConfiguration @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    static class TestApplication {}
    static class Adapter extends PostgresqlUndoLogManager {
        void insert(Connection c,String xid) throws SQLException { insertUndoLogWithNormal(xid,991L,"serializer=jackson",new byte[]{1},c); }
    }
    @Test void actualSeataAdapterCanInsertAndCleanUndoLog() throws Exception {
        var ds=new DriverManagerDataSource(System.getenv("PG_URL"),System.getenv("PG_USER"),System.getenv("PG_PASSWORD"));
        try(var c=ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                String xid="schema-test-"+UUID.randomUUID();new Adapter().insert(c,xid);
                try(var s=c.prepareStatement("SELECT id FROM undo_log WHERE xid=?")) {
                    s.setString(1,xid);try(var rows=s.executeQuery()) { assertTrue(rows.next());assertTrue(rows.getLong(1)>0); }
                }
            } finally { c.rollback(); }
        }
    }
}

package cn.demo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 直接使用两种真实数据库，避免 H2 隐藏 SQL 方言、锁和事务语义差异。
 *
 * @author liusw144
 */
@EnabledIfEnvironmentVariable(named = "DEMO_DB_TESTS", matches = "true")
class DatabaseContractTest {

    JdbcTemplate mysql, pg;

    BusinessRepository business;

    TaskStore tasks;

    TransactionTemplate tx;

    ObjectMapper json = new ObjectMapper();

    String id;

    @BeforeEach
    void setup() {
        var m = new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USER"),
                System.getenv("MYSQL_PASSWORD"));
        var p = new DriverManagerDataSource(System.getenv("PG_URL"), System.getenv("PG_USER"),
                System.getenv("PG_PASSWORD"));
        mysql = new JdbcTemplate(m);
        pg = new JdbcTemplate(p);
        tx = new TransactionTemplate(new DataSourceTransactionManager(m));
        business = new BusinessRepository(mysql, pg);
        tasks = new TaskStore(mysql, new DataSourceTransactionManager(m), json, new RetryPolicy(3, 1, 10));
        id = "t-" + UUID.randomUUID();
        mysql.update("INSERT INTO demo_stock(id,available) VALUES (?,10)", "TEST:" + id);
        pg.update("INSERT INTO demo_accounts(id,balance) VALUES (?,100.00)", "TEST:" + id);
    }

    Purchase request() {
        return new Purchase(id, id, id, 2, new BigDecimal("5.00"), Purchase.Failure.NONE);
    }

    @AfterEach
    void cleanup() {
        mysql.update("DELETE FROM demo_tasks WHERE id=?", id);
        mysql.update("DELETE FROM demo_orders WHERE order_id=?", id);
        mysql.update("DELETE FROM demo_stock WHERE id=?", "TEST:" + id);
        pg.update("DELETE FROM demo_receipts WHERE event_id=?", id);
        pg.update("DELETE FROM demo_accounts WHERE id=?", "TEST:" + id);
    }

    @Test
    void localRollbackRemovesOrderStockReservationAndOutboxTogether() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            business.insertOrder("TEST", request(), "PENDING");
            business.reduceStock("TEST", request());
            tasks.enqueue("TEST", request());
            throw new IllegalStateException("模拟提交前异常");
        }));
        assertTrue(business.orders("TEST").isEmpty());
        assertTrue(tasks.list("TEST").isEmpty());
        assertEquals(10,
                mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?", Integer.class, "TEST:" + id));
    }

    @Test
    void conditionalDebitNeverCreatesNegativeBalance() {
        assertTrue(business.debit("TEST", request()));
        var tooMuch = new Purchase(id, id, id, 100, new BigDecimal("5.00"), Purchase.Failure.NONE);
        assertFalse(business.debit("TEST", tooMuch));
        assertEquals(new BigDecimal("90.00"),
                pg.queryForObject("SELECT balance FROM demo_accounts WHERE id=?", BigDecimal.class, "TEST:" + id));
    }

    @Test
    void staleWorkerCannotCompleteTaskAndExpiredLastClaimBecomesDead() {
        tasks.enqueue("TEST", request());
        var first = tasks.claim("TEST").orElseThrow();
        mysql.update("UPDATE demo_tasks SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
        var second = tasks.claim("TEST").orElseThrow();
        assertFalse(tasks.complete(first, "PAID"));
        tasks.fail(second, new IllegalStateException("HTTP timeout"));
        mysql.update("UPDATE demo_tasks SET next_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        tasks.claim("TEST").orElseThrow();
        mysql.update("UPDATE demo_tasks SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", id);
        assertTrue(tasks.claim("TEST").isEmpty());
        assertEquals("DEAD", tasks.list("TEST").get(0).get("status"));
    }
}

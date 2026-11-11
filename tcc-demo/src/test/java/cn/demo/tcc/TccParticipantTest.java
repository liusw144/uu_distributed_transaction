package cn.demo.tcc;

import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import cn.demo.common.workflow.ProtocolParticipant;
import cn.demo.common.workflow.ProtocolParticipant.Resource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实两库验证锁、原子提交和资金守恒；每个用例只清理自己的随机资源。
 */
@EnabledIfEnvironmentVariable(named = "DEMO_DB_TESTS", matches = "true")
class TccParticipantTest {
    static JdbcTemplate mysql, pg;
    static PlatformTransactionManager mysqlTx, pgTx;
    ProtocolParticipant participant;
    String fixtureId;
    final List<String> orders = new ArrayList<>();

    @BeforeAll
    static void databases() {
        var mysqlDs = new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USER"), System.getenv("MYSQL_PASSWORD"));
        var pgDs = new DriverManagerDataSource(System.getenv("PG_URL"), System.getenv("PG_USER"), System.getenv("PG_PASSWORD"));
        new ResourceDatabasePopulator(new FileSystemResource("sql/mysql.sql")).execute(mysqlDs);
        new ResourceDatabasePopulator(new FileSystemResource("sql/postgresql.sql")).execute(pgDs);
        mysql = new JdbcTemplate(mysqlDs);
        pg = new JdbcTemplate(pgDs);
        mysqlTx = new DataSourceTransactionManager(mysqlDs);
        pgTx = new DataSourceTransactionManager(pgDs);
    }

    @BeforeEach
    void setup() {
        fixtureId = "t" + UUID.randomUUID().toString().replace("-", "");
        mysql.update("INSERT INTO tcc_stock(product_id,available) VALUES (?,100)", fixtureId);
        pg.update("INSERT INTO tcc_accounts(user_id,available) VALUES (?,10000)", fixtureId);
        participant = new TccParticipant(mysql, pg, mysqlTx, pgTx, new ObjectMapper());
    }

    @AfterEach
    void cleanup() {
        for (String order : orders) {
            mysql.update("DELETE FROM tcc_branches WHERE tx_id=?", order);
            pg.update("DELETE FROM tcc_branches WHERE tx_id=?", order);
        }
        if (fixtureId != null) {
            mysql.update("DELETE FROM tcc_stock WHERE product_id=?", fixtureId);
            pg.update("DELETE FROM tcc_accounts WHERE user_id=?", fixtureId);
        }
    }

    Purchase purchase() {
        String id = "tcc-" + UUID.randomUUID();
        orders.add(id);
        return new Purchase(id, fixtureId, fixtureId, 2, new BigDecimal("5.00"), Purchase.Failure.NONE);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void tryFreezesResourcesOnlyOnce(Resource resource) {
        Purchase p = purchase();
        participant.forward(resource, p);
        participant.forward(resource, new Purchase(p.orderId(), p.userId(), p.productId(), 2,
                new BigDecimal("5.0"), Purchase.Failure.AFTER_STOCK));
        assertResource(resource, "98", "2", "0", "9990", "10", "0");
        assertEquals("RESERVED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void confirmMovesFrozenResourcesOnlyOnce(Resource resource) {
        Purchase p = purchase();
        participant.forward(resource, p);
        // 重新创建参与者模拟进程重启，幂等性依靠持久化日志而不是 JVM 缓存。
        participant = new TccParticipant(mysql, pg, mysqlTx, pgTx, new ObjectMapper());
        participant.confirm(resource, p);
        participant = new TccParticipant(mysql, pg, mysqlTx, pgTx, new ObjectMapper());
        participant.confirm(resource, p);
        participant.forward(resource, p);
        assertResource(resource, "98", "0", "2", "9990", "0", "10");
        assertEquals("CONFIRMED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void cancelReleasesResourcesOnlyOnce(Resource resource) {
        Purchase p = purchase();
        participant.forward(resource, p);
        participant.reverse(resource, p);
        participant.reverse(resource, p);
        assertResource(resource, "100", "0", "0", "10000", "0", "0");
        assertEquals("CANCELLED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void emptyCancelCreatesTombstoneRejectingLateTry(Resource resource) {
        Purchase p = purchase();
        participant.reverse(resource, p);
        assertThrows(BusinessException.class, () -> participant.forward(resource, p));
        assertThrows(BusinessException.class, () -> participant.confirm(resource, p));
        assertResource(resource, "100", "0", "0", "10000", "0", "0");
        assertEquals("CANCELLED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void confirmedBranchCannotBeCancelled(Resource resource) {
        Purchase p = purchase();
        participant.forward(resource, p);
        participant.confirm(resource, p);
        assertThrows(BusinessException.class, () -> participant.reverse(resource, p));
        assertResource(resource, "98", "0", "2", "9990", "0", "10");
        assertEquals("CONFIRMED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void mismatchedPayloadCannotTryConfirmOrCancel(Resource resource) {
        Purchase p = purchase();
        participant.forward(resource, p);
        Purchase conflict = new Purchase(p.orderId(), p.userId(), p.productId(), 3, p.unitPrice(), p.failAt());
        assertThrows(BusinessException.class, () -> participant.forward(resource, conflict));
        assertThrows(BusinessException.class, () -> participant.confirm(resource, conflict));
        assertThrows(BusinessException.class, () -> participant.reverse(resource, conflict));
        assertResource(resource, "98", "2", "0", "9990", "10", "0");
        assertEquals("RESERVED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void concurrentDuplicateTryFreezesExactlyOnce(Resource resource) throws Exception {
        Purchase p = purchase();
        ExecutorService workers = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                results.add(workers.submit(() -> {
                    start.await();
                    participant.forward(resource, p);
                    return null;
                }));
            start.countDown();
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertResource(resource, "98", "2", "0", "9990", "10", "0");
        assertEquals(1, jdbc(resource).queryForObject("SELECT COUNT(*) FROM tcc_branches WHERE tx_id=?", Integer.class, p.orderId()));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void insufficientResourcesRollbackNewBranch(Resource resource) {
        Purchase p = purchase();
        if (resource == Resource.STOCK) mysql.update("UPDATE tcc_stock SET available=1 WHERE product_id=?", fixtureId);
        else pg.update("UPDATE tcc_accounts SET available=1 WHERE user_id=?", fixtureId);
        assertThrows(BusinessException.class, () -> participant.forward(resource, p));
        assertEquals(0, jdbc(resource).queryForObject("SELECT COUNT(*) FROM tcc_branches WHERE tx_id=?", Integer.class, p.orderId()));
        assertResource(resource, "1", "0", "0", "1", "0", "0");
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void concurrentDifferentOrdersCannotOverspend(Resource resource) throws Exception {
        if (resource == Resource.STOCK) mysql.update("UPDATE tcc_stock SET available=3 WHERE product_id=?", fixtureId);
        else pg.update("UPDATE tcc_accounts SET available=15 WHERE user_id=?", fixtureId);
        List<Purchase> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) requests.add(purchase());
        ExecutorService workers = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        int successes = 0;
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Purchase p : requests)
                results.add(workers.submit(() -> {
                    start.await();
                    try {
                        participant.forward(resource, p);
                        return true;
                    } catch (BusinessException insufficient) {
                        return false;
                    }
                }));
            start.countDown();
            for (Future<Boolean> result : results) if (result.get(20, TimeUnit.SECONDS)) successes++;
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, successes);
        assertResource(resource, "1", "2", "0", "5", "10", "0");
        int branches = 0;
        for (Purchase p : requests)
            branches += jdbc(resource).queryForObject(
                    "SELECT COUNT(*) FROM tcc_branches WHERE tx_id=?", Integer.class, p.orderId());
        assertEquals(1, branches, "失败的并发预留不能留下 NEW 日志");
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void concurrentCancelAndTryAlwaysEndReleased(Resource resource) throws Exception {
        Purchase p = purchase();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> forward = workers.submit(() -> {
                start.await();
                try {
                    participant.forward(resource, p);
                } catch (BusinessException cancelledFirst) { /* Cancel 抢先成功时，迟到 Try 应被拒绝。 */ }
                return null;
            });
            Future<?> cancel = workers.submit(() -> {
                start.await();
                participant.reverse(resource, p);
                return null;
            });
            start.countDown();
            forward.get(20, TimeUnit.SECONDS);
            cancel.get(20, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertResource(resource, "100", "0", "0", "10000", "0", "0");
        assertEquals("CANCELLED", state(resource, p));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void confirmBeforeTryDoesNotInventReservation(Resource resource) {
        Purchase p = purchase();
        assertThrows(BusinessException.class, () -> participant.confirm(resource, p));
        assertEquals(0, jdbc(resource).queryForObject("SELECT COUNT(*) FROM tcc_branches WHERE tx_id=?", Integer.class, p.orderId()));
        assertResource(resource, "100", "0", "0", "10000", "0", "0");
    }

    @Test
    void snapshotExposesBothResourcesAndBranchJournals() {
        Purchase p = purchase();
        participant.forward(Resource.STOCK, p);
        participant.forward(Resource.ACCOUNT, p);
        assertEquals("TCC", participant.protocol());
        var snapshot = participant.snapshot();
        assertTrue(snapshot.get("stock") instanceof List<?>);
        assertTrue(snapshot.get("accounts") instanceof List<?>);
        assertNotNull(snapshot.get("branches"));
        assertTrue(snapshot.toString().contains(p.orderId()));
        assertTrue(snapshot.toString().contains(fixtureId));
    }

    JdbcTemplate jdbc(Resource resource) {
        return resource == Resource.STOCK ? mysql : pg;
    }

    String state(Resource resource, Purchase p) {
        return jdbc(resource).queryForObject("SELECT state FROM tcc_branches WHERE tx_id=?", String.class, p.orderId());
    }

    void assertResource(Resource resource, String sa, String sr, String sc, String aa, String ar, String ac) {
        String table = resource == Resource.STOCK ? "tcc_stock" : "tcc_accounts";
        String key = resource == Resource.STOCK ? "product_id" : "user_id";
        var row = jdbc(resource).queryForMap("SELECT available,reserved,confirmed FROM " + table + " WHERE " + key + "=?", fixtureId);
        String[] expected = resource == Resource.STOCK ? new String[]{sa, sr, sc} : new String[]{aa, ar, ac};
        String[] fields = {"available", "reserved", "confirmed"};
        for (int i = 0; i < fields.length; i++) {
            assertEquals(0, new BigDecimal(expected[i]).compareTo(new BigDecimal(row.get(fields[i]).toString())), fields[i]);
        }
    }
}

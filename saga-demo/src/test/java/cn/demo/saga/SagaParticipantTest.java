package cn.demo.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import cn.demo.common.workflow.ProtocolParticipant.Resource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 使用真实 MySQL/PG 验证唯一键、行锁与本地事务，不用内存数据库模拟并发。
 *
 * @author liusw144
 */
@EnabledIfEnvironmentVariable(named = "DEMO_DB_TESTS", matches = "true")
class SagaParticipantTest {

    private static final int CONCURRENT_REQUEST_COUNT = 8;

    private static final int TASK_QUEUE_CAPACITY = 32;

    private static final String STOCK_SNAPSHOT_KEY = "stock";

    private static final String ACCOUNTS_SNAPSHOT_KEY = "accounts";

    static DriverManagerDataSource mysqlSource;

    static DriverManagerDataSource pgSource;

    JdbcTemplate mysql;

    JdbcTemplate pg;

    SagaParticipant participant;

    String id;

    final List<String> actionIds = new ArrayList<>();

    @BeforeAll
    static void schema() throws Exception {
        mysqlSource = source("MYSQL");
        pgSource = source("PG");
        // 仅创建增量表与种子，不清空专用库，不重置已有资源。
        try (var connection = mysqlSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("sql/mysql.sql")));
        }
        try (var connection = pgSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("sql/postgresql.sql")));
        }
    }

    static DriverManagerDataSource source(String prefix) {
        return new DriverManagerDataSource(System.getenv(prefix + "_URL"), System.getenv(prefix + "_USER"),
                System.getenv(prefix + "_PASSWORD"));
    }

    @BeforeEach
    void setup() {
        mysql = new JdbcTemplate(mysqlSource);
        pg = new JdbcTemplate(pgSource);
        participant = new SagaParticipant(mysql, pg, new DataSourceTransactionManager(mysqlSource),
                new DataSourceTransactionManager(pgSource), new ObjectMapper());
        id = "s-" + UUID.randomUUID();
        actionIds.add(id);
        mysql.update("INSERT INTO demo_stock(id,available) VALUES (?,100)", "SAGA:" + id);
        pg.update("INSERT INTO demo_accounts(id,balance) VALUES (?,1000.00)", "SAGA:" + id);
    }

    @AfterEach
    void cleanup() {
        if (mysql == null) {
            return;
        }
        for (String actionId : actionIds) {
            mysql.update("DELETE FROM saga_stock_actions WHERE tx_id=?", actionId);
            pg.update("DELETE FROM saga_account_actions WHERE tx_id=?", actionId);
        }
        mysql.update("DELETE FROM demo_stock WHERE id=?", "SAGA:" + id);
        pg.update("DELETE FROM demo_accounts WHERE id=?", "SAGA:" + id);
    }

    Purchase purchase() {
        return new Purchase(id, id, id, 2, new BigDecimal("5.00"), Purchase.Failure.NONE);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void duplicateForwardDebitsOnlyOnce(Resource resource) {
        participant.forward(resource, purchase());
        participant.forward(resource, purchase());
        assertDebited(resource);
        assertEquals("APPLIED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void equivalentBusinessPayloadCanRetryDespiteFailureFlagAndPriceScale(Resource resource) {
        participant.forward(resource, purchase());
        participant.forward(resource, new Purchase(id, id, id, 2, new BigDecimal("5.0"), Purchase.Failure.AFTER_STOCK));
        assertDebited(resource);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void repeatedCompensationRestoresResourceExactlyOnce(Resource resource) {
        participant.forward(resource, purchase());
        participant.reverse(resource, purchase());
        participant.reverse(resource, purchase());
        assertInitial(resource);
        assertEquals("COMPENSATED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void emptyCompensationCreatesTombstoneAndBlocksLateForward(Resource resource) {
        participant.reverse(resource, purchase());
        participant.reverse(resource, purchase());
        assertThrows(BusinessException.class, () -> participant.forward(resource, purchase()));
        assertInitial(resource);
        assertEquals("COMPENSATED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void completedCompensationBlocksLateForward(Resource resource) {
        participant.forward(resource, purchase());
        participant.reverse(resource, purchase());
        assertThrows(BusinessException.class, () -> participant.forward(resource, purchase()));
        assertInitial(resource);
        assertEquals("COMPENSATED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void conflictingForwardPayloadCannotReuseActionId(Resource resource) {
        participant.forward(resource, purchase());
        var changed = new Purchase(id, id, id, 3, new BigDecimal("5.00"), Purchase.Failure.NONE);
        assertThrows(BusinessException.class, () -> participant.forward(resource, changed));
        assertDebited(resource);
        assertEquals("APPLIED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void conflictingCompensationCannotRefundAnotherPayload(Resource resource) {
        participant.forward(resource, purchase());
        var changed = new Purchase(id, id, id, 3, new BigDecimal("5.00"), Purchase.Failure.NONE);
        assertThrows(BusinessException.class, () -> participant.reverse(resource, changed));
        assertDebited(resource);
        assertEquals("APPLIED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void concurrentDuplicateForwardCommitsOneDebit(Resource resource) throws Exception {
        concurrent(12, () -> participant.forward(resource, purchase()));
        assertDebited(resource);
        assertEquals(1, actionCount(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void concurrentDuplicateCompensationCommitsOneRefund(Resource resource) throws Exception {
        participant.forward(resource, purchase());
        concurrent(12, () -> participant.reverse(resource, purchase()));
        assertInitial(resource);
        assertEquals("COMPENSATED", state(resource, id));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void insufficientResourceRollsBackNewActionSoItCanRetry(Resource resource) {
        if (resource == Resource.STOCK) {
            mysql.update("UPDATE demo_stock SET available=1 WHERE id=?", "SAGA:" + id);
        } else {
            pg.update("UPDATE demo_accounts SET balance=1.00 WHERE id=?", "SAGA:" + id);
        }
        assertThrows(BusinessException.class, () -> participant.forward(resource, purchase()));
        assertEquals(0, actionCount(resource, id));
        assertEquals(new BigDecimal("1"), remaining(resource).stripTrailingZeros());
        if (resource == Resource.STOCK) {
            mysql.update("UPDATE demo_stock SET available=100 WHERE id=?", "SAGA:" + id);
        } else {
            pg.update("UPDATE demo_accounts SET balance=1000.00 WHERE id=?", "SAGA:" + id);
        }
        participant.forward(resource, purchase());
        assertDebited(resource);
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void concurrentDistinctOrdersCannotOverdraw(Resource resource) throws Exception {
        var requests = new ArrayList<Purchase>();
        for (int requestIndex = 0; requestIndex < CONCURRENT_REQUEST_COUNT; requestIndex++) {
            String txId = "s-" + UUID.randomUUID();
            actionIds.add(txId);
            requests.add(new Purchase(txId, id, id, 70, new BigDecimal("10.00"), Purchase.Failure.NONE));
        }
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        concurrent(CONCURRENT_REQUEST_COUNT, () -> {
            Purchase request = requests.get(cursor.getAndIncrement());
            try {
                participant.forward(resource, request);
                success.incrementAndGet();
            } catch (BusinessException insufficient) {
                // 失败分支必须回滚 NEW 流水，才能安全地重复请求。
                assertEquals(0, actionCount(resource, request.orderId()));
            }
        });
        assertEquals(1, success.get());
        assertEquals(0, (resource == Resource.STOCK ? new BigDecimal("30") : new BigDecimal("300"))
                .compareTo(remaining(resource)));
    }

    @ParameterizedTest
    @EnumSource(Resource.class)
    void failedCompensationKeepsAppliedStateForRetry(Resource resource) {
        participant.forward(resource, purchase());
        if (resource == Resource.STOCK) {
            mysql.update("DELETE FROM demo_stock WHERE id=?", "SAGA:" + id);
        } else {
            pg.update("DELETE FROM demo_accounts WHERE id=?", "SAGA:" + id);
        }
        assertThrows(BusinessException.class, () -> participant.reverse(resource, purchase()));
        assertEquals("APPLIED", state(resource, id));
        if (resource == Resource.STOCK) {
            mysql.update("INSERT INTO demo_stock(id,available) VALUES (?,98)", "SAGA:" + id);
        } else {
            pg.update("INSERT INTO demo_accounts(id,balance) VALUES (?,990.00)", "SAGA:" + id);
        }
        participant.reverse(resource, purchase());
        assertInitial(resource);
    }

    @Test
    void snapshotExposesActualResourceAndActionState() {
        participant.forward(Resource.STOCK, purchase());
        participant.forward(Resource.ACCOUNT, purchase());
        Map<String, Object> snapshot = participant.snapshot();
        assertEquals("SAGA", participant.protocol());
        assertTrue(((List<?>) snapshot.get(STOCK_SNAPSHOT_KEY)).stream().map(Map.class::cast).anyMatch(
                row -> ("SAGA:" + id).equals(row.get("id")) && ((Number) row.get("available")).intValue() == 98));
        assertTrue(((List<?>) snapshot.get(ACCOUNTS_SNAPSHOT_KEY)).stream().map(Map.class::cast).anyMatch(
                row -> ("SAGA:" + id).equals(row.get("id")) && new BigDecimal("990.00").equals(row.get("balance"))));
        Map<?, ?> branches = (Map<?, ?>) snapshot.get("branches");
        for (String branch : List.of(STOCK_SNAPSHOT_KEY, ACCOUNTS_SNAPSHOT_KEY)) {
            assertTrue(((List<?>) branches.get(branch)).stream().map(Map.class::cast)
                    .anyMatch(row -> id.equals(row.get("tx_id")) && "APPLIED".equals(row.get("state"))));
        }
    }

    @Test
    void sagaHasNoConfirmPhase() {
        assertThrows(UnsupportedOperationException.class, () -> participant.confirm(Resource.STOCK, purchase()));
        assertInitial(Resource.STOCK);
        assertInitial(Resource.ACCOUNT);
    }

    private static void concurrent(int count, Runnable operation) throws Exception {
        ExecutorService pool = workerPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            try {
                for (int taskIndex = 0; taskIndex < count; taskIndex++) {
                    tasks.add(pool.submit(() -> {
                        try {
                            if (!start.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("并发任务未能同时启动");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("等待并发测试启动信号时被中断", error);
                        }
                        operation.run();
                    }));
                }
            } finally {
                start.countDown();
            }
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    JdbcTemplate database(Resource resource) {
        return resource == Resource.STOCK ? mysql : pg;
    }

    String table(Resource resource) {
        return resource == Resource.STOCK ? "saga_stock_actions" : "saga_account_actions";
    }

    String state(Resource resource, String txId) {
        return database(resource).queryForObject("SELECT state FROM " + table(resource) + " WHERE tx_id=?",
                String.class, txId);
    }

    int actionCount(Resource resource, String txId) {
        return database(resource).queryForObject("SELECT COUNT(*) FROM " + table(resource) + " WHERE tx_id=?",
                Integer.class, txId);
    }

    BigDecimal remaining(Resource resource) {
        return resource == Resource.STOCK
                ? BigDecimal.valueOf(mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?", Integer.class,
                        "SAGA:" + id))
                : pg.queryForObject("SELECT balance FROM demo_accounts WHERE id=?", BigDecimal.class, "SAGA:" + id);
    }

    void assertDebited(Resource resource) {
        assertEquals(0, (resource == Resource.STOCK ? new BigDecimal("98") : new BigDecimal("990"))
                .compareTo(remaining(resource)));
    }

    void assertInitial(Resource resource) {
        assertEquals(0, (resource == Resource.STOCK ? new BigDecimal("100") : new BigDecimal("1000"))
                .compareTo(remaining(resource)));
    }

    private static ExecutorService workerPool(int threadCount) {
        return new ThreadPoolExecutor(threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(TASK_QUEUE_CAPACITY), new TestThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 为并发数据库测试创建具名线程，保留默认工厂的线程属性。
     *
     * @author liusw144
     */
    private static final class TestThreadFactory implements ThreadFactory {
        private final ThreadFactory delegate = Executors.defaultThreadFactory();

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable operation) {
            Thread worker = delegate.newThread(operation);
            worker.setName("saga-participant-test-" + sequence.incrementAndGet());
            return worker;
        }
    }
}

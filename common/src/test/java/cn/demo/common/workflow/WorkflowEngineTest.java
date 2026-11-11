package cn.demo.common.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import cn.demo.common.BusinessException;
import cn.demo.common.BusinessRepository;
import cn.demo.common.Purchase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 验证WorkflowEngine的事务行为与边界条件。
 *
 * @author liusw144
 */
@EnabledIfEnvironmentVariable(named = "DEMO_DB_TESTS", matches = "true")
class WorkflowEngineTest {
    private static final int ACCOUNT_STEP = 2;

    JdbcTemplate mysql;

    WorkflowStore store;

    WorkflowFaults faults;

    MemoryParticipant participant;

    WorkflowEngine engine;

    String id;

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USER"),
                System.getenv("MYSQL_PASSWORD"));
        mysql = new JdbcTemplate(dataSource);
        store = new WorkflowStore(mysql, new DataSourceTransactionManager(dataSource), dataSource, new ObjectMapper(),
                new BusinessRepository(mysql, mysql));
        faults = new WorkflowFaults();
        participant = new MemoryParticipant("TCC");
        engine = new WorkflowEngine(store, participant, faults, 2, 60);
        id = "w-" + UUID.randomUUID();
    }

    Purchase createPurchase(Purchase.Failure failure) {
        return new Purchase(id, "U1001", "SKU-001", 2, new BigDecimal("10.00"), failure);
    }

    @AfterEach
    void cleanup() {
        mysql.update("DELETE FROM demo_workflows WHERE id=?", id);
        mysql.update("DELETE FROM demo_orders WHERE order_id=?", id);
    }

    @Test
    void manualPrepareThenConfirmHasExactlyOneEffect() {
        assertEquals("PREPARED", engine.start(createPurchase(Purchase.Failure.NONE), true).status());
        assertEquals(8, participant.stock);
        assertEquals(new BigDecimal("80.00"), participant.money);
        assertEquals("SUCCEEDED", engine.confirm(id).status());
        assertEquals("SUCCEEDED", engine.confirm(id).status());
        assertEquals(8, participant.stock);
        assertEquals("PAID", mysql.queryForObject("SELECT status FROM demo_orders WHERE order_id=?", String.class, id));
    }

    @Test
    void failureAfterAccountCancelsBothParticipants() {
        assertEquals("CANCELLED", engine.start(createPurchase(Purchase.Failure.AFTER_ACCOUNT), false).status());
        assertEquals(10, participant.stock);
        assertEquals(new BigDecimal("100.00"), participant.money);
        assertEquals("CANCELLED",
                mysql.queryForObject("SELECT status FROM demo_orders WHERE order_id=?", String.class, id));
    }

    @Test
    void lostConfirmAckCanResumeWithNewCoordinatorButCannotChooseCancel() {
        engine.start(createPurchase(Purchase.Failure.NONE), true);
        faults.configure("confirm", 0, 1);
        assertEquals("CONFIRMING", engine.confirm(id).status());
        assertThrows(BusinessException.class, () -> engine.cancel(id));
        var restarted = new WorkflowEngine(store, participant, new WorkflowFaults(), 2, 60);
        assertEquals("SUCCEEDED", restarted.retry(id).status());
        assertEquals(8, participant.stock);
        assertEquals(new BigDecimal("80.00"), participant.money);
    }

    @Test
    void exhaustedConfirmRetainsDecisionUntilManualRetry() {
        engine.start(createPurchase(Purchase.Failure.NONE), true);
        faults.configure("confirm", 100, 0);
        assertEquals("CONFIRMING", engine.confirm(id).status());
        mysql.update("UPDATE demo_workflows SET next_retry_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
        engine.recover();
        assertTrue(engine.get(id).blocked());
        assertEquals("CONFIRMING", engine.get(id).status());
        faults.configure("confirm", 0, 0);
        assertEquals("SUCCEEDED", engine.retry(id).status());
    }

    @Test
    void manualReservationExpiresAndReleasesResources() {
        engine.start(createPurchase(Purchase.Failure.NONE), true);
        mysql.update("UPDATE demo_workflows SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                id);
        engine.recover();
        assertEquals("CANCELLED", engine.get(id).status());
        assertEquals(10, participant.stock);
        assertEquals(new BigDecimal("100.00"), participant.money);
        assertThrows(BusinessException.class, () -> engine.confirm(id));
    }

    @Test
    void duplicateStartDoesNotChangePayloadOrReserveAgain() {
        engine.start(createPurchase(Purchase.Failure.NONE), true);
        assertEquals("PREPARED", engine.start(createPurchase(Purchase.Failure.NONE), false).status());
        assertEquals(8, participant.stock);
        var different = new Purchase(id, "U1001", "SKU-001", 1, new BigDecimal("10.00"), Purchase.Failure.NONE);
        assertThrows(BusinessException.class, () -> engine.start(different, true));
    }

    @Test
    void sagaCompensationRetriesAfterLostAckWithoutRefundingTwice() {
        participant = new MemoryParticipant("SAGA");
        engine = new WorkflowEngine(store, participant, faults, 2, 60);
        faults.configure("compensate", 0, 1);
        assertEquals("COMPENSATING", engine.start(createPurchase(Purchase.Failure.AFTER_ACCOUNT), false).status());
        assertEquals(new BigDecimal("100.00"), participant.money);
        assertEquals(8, participant.stock);
        assertEquals("COMPENSATED",
                new WorkflowEngine(store, participant, new WorkflowFaults(), 2, 60).retry(id).status());
        assertEquals(10, participant.stock);
        assertEquals(new BigDecimal("100.00"), participant.money);
        assertEquals("COMPENSATED", engine.retry(id).status());
    }

    @Test
    void sagaSuccessCommitsAndRejectsTccCommands() {
        participant = new MemoryParticipant("SAGA");
        engine = new WorkflowEngine(store, participant, faults, 2, 60);
        assertEquals("SUCCEEDED", engine.start(createPurchase(Purchase.Failure.NONE), false).status());
        assertThrows(BusinessException.class, () -> engine.confirm(id));
        assertThrows(BusinessException.class, () -> engine.cancel(id));
        assertEquals(8, participant.stock);
        assertEquals(new BigDecimal("80.00"), participant.money);
    }

    @Test
    void deadlineExpiringDuringTryCannotBecomeCommitDecision() {
        participant = new MemoryParticipant("TCC") {

            @Override
            public void forward(Resource resource, Purchase purchase) {
                super.forward(resource, purchase);
                if (resource == Resource.ACCOUNT) {
                    mysql.update("UPDATE demo_workflows SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) "
                            + "WHERE id=?", id);
                }
            }
        };
        engine = new WorkflowEngine(store, participant, faults, 2, 60);
        assertEquals("CANCELLED", engine.start(createPurchase(Purchase.Failure.NONE), false).status());
        assertEquals(10, participant.stock);
        assertEquals(new BigDecimal("100.00"), participant.money);
    }

    @Test
    void staleCoordinatorCannotReplacePersistedConfirmWithCancel() {
        store = storeWithRace("CONFIRMING", true);
        engine = new WorkflowEngine(store, participant, faults, 2, 60);
        assertEquals("SUCCEEDED", engine.start(createPurchase(Purchase.Failure.NONE), false).status());
        assertEquals("CONFIRMED", participant.states.get(ProtocolParticipant.Resource.STOCK));
        assertEquals("CONFIRMED", participant.states.get(ProtocolParticipant.Resource.ACCOUNT));
        assertEquals(new BigDecimal("80.00"), participant.money);
    }

    @Test
    void staleSagaCannotMarkCompensatingWorkflowSuccessful() {
        participant = new MemoryParticipant("SAGA");
        store = storeWithRace("COMPENSATING", false);
        engine = new WorkflowEngine(store, participant, faults, 2, 60);
        assertEquals("COMPENSATED", engine.start(createPurchase(Purchase.Failure.NONE), false).status());
        assertEquals(10, participant.stock);
        assertEquals(new BigDecimal("100.00"), participant.money);
    }

    @Test
    void confirmDecisionCannotUseStaleExpirySnapshot() {
        engine.start(createPurchase(Purchase.Failure.NONE), true);
        var stale = engine.get(id);
        mysql.update("UPDATE demo_workflows SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                id);
        assertFalse(store.transition(stale, "CONFIRMING", null));
        engine.recover();
        assertEquals("CANCELLED", engine.get(id).status());
    }

    @Test
    void sqlEquivalentIdsUseTheSameSessionLock() {
        engine.start(createPurchase(Purchase.Failure.NONE), true);
        String upper = id.toUpperCase(Locale.ROOT);
        String equivalent = mysql.queryForObject("SELECT count(*) FROM demo_workflows WHERE id=?", Integer.class,
                upper) == 1 ? upper : id;
        assertTrue(store.withLock(id, () -> assertFalse(store.withLock(equivalent, () -> {
        }))));
    }

    private WorkflowStore storeWithRace(String decision, boolean confirmStock) {
        var dataSource = new DriverManagerDataSource(System.getenv("MYSQL_URL"), System.getenv("MYSQL_USER"),
                System.getenv("MYSQL_PASSWORD"));
        return new WorkflowStore(mysql, new DataSourceTransactionManager(dataSource), dataSource, new ObjectMapper(),
                new BusinessRepository(mysql, mysql)) {

            @Override
            public boolean advance(Flow flow, int next) {
                boolean result = super.advance(flow, next);
                if (result && next == ACCOUNT_STEP) {
                    // 确定性模拟：会话锁丢失后，另一驱动已经落库决议，旧调用随后继续执行。
                    mysql.update(
                            "UPDATE demo_workflows SET status=?,"
                                    + "expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",
                            decision, id);
                    if (confirmStock) {
                        participant.confirm(ProtocolParticipant.Resource.STOCK, flow.purchase());
                    }
                }
                return result;
            }
        };
    }

    /**
     * 仅隔离协调逻辑；真实数据库的资源与流水原子性由两个模块的分支测试覆盖。
     *
     * @author liusw144
     */
    static class MemoryParticipant implements ProtocolParticipant {
        private static final String STATE_REVERSED = "REVERSED";
        private static final String STATE_CONFIRMED = "CONFIRMED";
        private static final String STATE_APPLIED = "APPLIED";

        final String protocol;

        final Map<Resource, String> states = new EnumMap<>(Resource.class);

        int stock = 10;

        BigDecimal money = new BigDecimal("100.00");

        MemoryParticipant(String protocol) {
            this.protocol = protocol;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public void forward(Resource resource, Purchase purchase) {
            String state = states.get(resource);
            if (STATE_REVERSED.equals(state)) {
                throw new BusinessException("迟到动作");
            }
            if (state != null) {
                return;
            }
            if (resource == Resource.STOCK) {
                stock -= purchase.quantity();
            } else {
                money = money.subtract(purchase.amount());
            }
            states.put(resource, STATE_APPLIED);
        }

        @Override
        public void confirm(Resource resource, Purchase purchase) {
            if (STATE_REVERSED.equals(states.get(resource))) {
                throw new BusinessException("已取消");
            }
            states.put(resource, STATE_CONFIRMED);
        }

        @Override
        public void reverse(Resource resource, Purchase purchase) {
            String state = states.get(resource);
            if (STATE_CONFIRMED.equals(state)) {
                throw new BusinessException("已确认");
            }
            if (STATE_APPLIED.equals(state)) {
                if (resource == Resource.STOCK) {
                    stock += purchase.quantity();
                } else {
                    money = money.add(purchase.amount());
                }
            }
            states.put(resource, STATE_REVERSED);
        }

        @Override
        public Map<String, Object> snapshot() {
            return Map.of("stock", stock, "money", money);
        }
    }
}

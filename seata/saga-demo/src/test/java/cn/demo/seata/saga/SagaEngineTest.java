package cn.demo.seata.saga;

import cn.demo.common.Purchase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;

/**
 * 只使用独立资源编号，真实数据库验证状态机持久化与补偿。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DEMO_SEATA_TESTS", matches = "true")
class SagaEngineTest {
    @Autowired
    SagaService service;
    @SpyBean
    SagaParticipant participant;
    @Autowired
    @Qualifier("sagaMysql")
    DataSource mysql;
    @Autowired
    ApplicationContext context;
    @Autowired
    ObjectMapper json;

    Purchase purchase(Purchase.Failure failure) {
        String key = UUID.randomUUID().toString().replace("-", "");
        participant.seed(key, key, 100, new BigDecimal("1000"));
        return new Purchase("sg_" + key, key, key, 2, new BigDecimal("10.00"), failure);
    }

    @Test
    void successAndIdempotency() {
        var p = purchase(Purchase.Failure.NONE);
        var first = service.submit(p);
        assertThat(first.get("status")).isEqualTo("SU");
        assertThat(service.submit(p).get("id")).isEqualTo(first.get("id"));
        assertThat(participant.stockAvailable(p.productId())).isEqualTo(98);
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("980");
        assertThatThrownBy(() -> service.submit(new Purchase(p.orderId(), p.userId(), p.productId(), 3, p.unitPrice(), p.failAt())))
                .hasMessageContaining("不同");
    }

    @Test
    void afterOrder() {
        rollback(Purchase.Failure.AFTER_ORDER);
    }

    @Test
    void afterStock() {
        rollback(Purchase.Failure.AFTER_STOCK);
    }

    @Test
    void afterAccount() {
        rollback(Purchase.Failure.AFTER_ACCOUNT);
    }

    void rollback(Purchase.Failure point) {
        var p = purchase(point);
        var result = service.submit(p);
        assertThat(result.get("compensationStatus")).isEqualTo("SU");
        assertThat(result.get("states").toString()).contains("After" + switch (point) {
            case AFTER_ORDER -> "Order";
            case AFTER_STOCK -> "Stock";
            default -> "Account";
        });
        assertThat(participant.stockAvailable(p.productId())).isEqualTo(100);
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
        assertThat(participant.orderState(p.orderId())).isEqualTo("COMPENSATED");
    }

    @Test
    void insufficientBalance() {
        var p = purchase(Purchase.Failure.NONE);
        participant.seed(p.productId(), p.userId(), 100, BigDecimal.ONE);
        var result = service.submit(p);
        assertThat(result.get("compensationStatus")).isEqualTo("SU");
        assertThat(participant.stockAvailable(p.productId())).isEqualTo(100);
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("1");
    }

    @Test
    void insufficientStockDoesNotDebit() {
        var p = purchase(Purchase.Failure.NONE);
        participant.seed(p.productId(), p.userId(), 1, new BigDecimal("1000"));
        var result = service.submit(p);
        assertThat(result.get("status")).isNotEqualTo("SU");
        assertThat(result.get("compensationStatus")).isEqualTo("SU");
        assertThat(participant.stockAvailable(p.productId())).isEqualTo(1);
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
        assertThat(participant.orderState(p.orderId())).isEqualTo("COMPENSATED");
    }

    @Test
    void successCannotBeCancelledByRecoveryApi() {
        var p = purchase(Purchase.Failure.NONE);
        var success = service.submit(p);
        assertThatThrownBy(() -> service.compensate(success.get("id").toString())).hasMessageContaining("成功订单");
        assertThat(participant.stockAvailable(p.productId())).isEqualTo(98);
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("980");
    }

    @Test
    void failedCompensationRecoversWithNewEngine() throws Exception {
        var p = purchase(Purchase.Failure.AFTER_ACCOUNT);
        participant.failNext(p.orderId(), "ACCOUNT", false);
        var failed = service.submit(p);
        String id = failed.get("id").toString();
        assertThat(failed.get("compensationStatus")).isNotEqualTo("SU");
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("980");
        service.rebuildEngine(); // 丢弃全部内存引擎及定义缓存，只从数据库重建。
        assertThat(service.compensate(id).get("compensationStatus")).isEqualTo("SU");
        assertThat(service.compensate(id).get("compensationStatus")).isEqualTo("SU");
        assertThat(participant.stockAvailable(p.productId())).isEqualTo(100);
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
    }

    @Test
    void lostCompensationAckIsIdempotent() throws Exception {
        var p = purchase(Purchase.Failure.AFTER_ACCOUNT);
        participant.failNext(p.orderId(), "ACCOUNT", true);
        var failed = service.submit(p);
        String id = failed.get("id").toString();
        assertThat(failed.get("compensationStatus")).isNotEqualTo("SU");
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
        service.rebuildEngine();
        assertThat(service.compensate(id).get("compensationStatus")).isEqualTo("SU");
        assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
    }

    @Test
    void interruptedBeforeFirstNodeCannotBecomeFalseSuccess() {
        var p = purchase(Purchase.Failure.NONE);
        var db = new JdbcTemplate(mysql);
        String id = "interrupted_" + UUID.randomUUID().toString().replace("-", "");
        String definition = db.queryForObject("SELECT id FROM seata_saga_state_machine_def WHERE name='seataSagaPurchase' AND tenant_id='seata-saga-demo' ORDER BY gmt_create DESC LIMIT 1", String.class);
        var serializer = new io.seata.saga.engine.serializer.impl.ParamsSerializer();
        serializer.setJsonParserName("jackson");
        var input = new java.util.HashMap<String, Object>();
        input.put("orderId", p.orderId());
        input.put("userId", p.userId());
        input.put("productId", p.productId());
        input.put("quantity", p.quantity());
        input.put("unitPrice", p.unitPrice().toPlainString());
        input.put("amount", p.amount().toPlainString());
        input.put("failAt", p.failAt().name());
        input.put("payload", "fixture");
        String serialized = serializer.serialize(new java.util.HashMap<>(Map.of("p", input)));
        // 模拟官方已持久化实例启动、尚未持久化第一个节点时进程退出的数据库边界。
        db.update("INSERT INTO seata_saga_state_machine_inst(id,machine_id,tenant_id,business_key,gmt_started,gmt_updated,status,is_running,start_params) VALUES (?,?,'seata-saga-demo',?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'RU',1,?)",
                id, definition, p.orderId(), serialized);
        try {
            assertThatThrownBy(() -> service.compensate(id)).hasMessageContaining("正向流程未完成");
            var row = db.queryForMap("SELECT status,compensation_status,is_running FROM seata_saga_state_machine_inst WHERE id=?", id);
            assertThat(row.get("status")).isEqualTo("RU");
            assertThat(row.get("compensation_status")).isNull();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM seata_saga_state_inst WHERE machine_inst_id=?", Integer.class, id)).isZero();
            assertThat(participant.stockAvailable(p.productId())).isEqualTo(100);
            assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
        } finally {
            // 只清理本测试生成的空实例，避免留下虚构进行中的实例。
            db.update("DELETE FROM seata_saga_state_machine_inst WHERE id=?", id);
        }
    }

    @Test
    void anotherEngineCannotCompensateAnActiveForwardExecution() throws Exception {
        var p = purchase(Purchase.Failure.NONE);
        var other = new SagaService(mysql, context, json); // 同数据库、独立引擎和独立服务锁。
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var startedRecovery = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            Map<String, Object> input = call.getArgument(0);
            if (p.orderId().equals(input.get("orderId"))) {
                entered.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("测试没有释放正向节点");
            }
            return call.callRealMethod();
        }).when(participant).afterStock(anyMap());
        Future<Map<String, Object>> forward = null;
        try {
            forward = workers.submit(() -> service.submit(p));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            String id = new JdbcTemplate(mysql).queryForObject(
                    "SELECT id FROM seata_saga_state_machine_inst WHERE business_key=?", String.class, p.orderId());
            var recovery = workers.submit(() -> {
                startedRecovery.countDown();
                try {
                    other.compensate(id);
                    return "补偿意外成功";
                } catch (cn.demo.common.BusinessException expected) {
                    return expected.getMessage();
                }
            });
            assertThat(startedRecovery.await(5, TimeUnit.SECONDS)).isTrue();
            // 另一个引擎必须等待业务锁；不能拿旧 RU snapshot 立即开始补偿。
            assertThatThrownBy(() -> recovery.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(participant.stockAvailable(p.productId())).isEqualTo(98);
            assertThat(participant.balance(p.userId())).isEqualByComparingTo("1000");
            release.countDown();
            assertThat(forward.get(10, TimeUnit.SECONDS).get("status")).isEqualTo("SU");
            assertThat(recovery.get(10, TimeUnit.SECONDS)).contains("成功订单");
            assertThat(participant.stockAvailable(p.productId())).isEqualTo(98);
            assertThat(participant.balance(p.userId())).isEqualByComparingTo("980");
        } finally {
            release.countDown();
            if (forward != null) try {
                forward.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}

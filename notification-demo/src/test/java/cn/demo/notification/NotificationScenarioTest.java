package cn.demo.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import cn.demo.common.ReceiptService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 验证NotificationScenario的事务行为与边界条件。
 *
 * @author liusw144
 */
@EnabledIfEnvironmentVariable(named = "DEMO_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "demo.worker.enabled=false")
class NotificationScenarioTest {

    @Autowired
    NotificationService service;

    @Autowired
    ReceiptService receipts;

    @Autowired
    @Qualifier("mysqlJdbc")
    JdbcTemplate mysql;

    @Autowired
    @Qualifier("pgJdbc")
    JdbcTemplate pg;

    String id;

    Purchase p;

    @BeforeEach
    void setup() {
        id = "n-" + UUID.randomUUID();
        p = new Purchase(id, "U1001", "SKU-001", 1, new BigDecimal("10.00"), Purchase.Failure.NONE);
    }

    @AfterEach
    void cleanup() {
        mysql.update("DELETE FROM demo_tasks WHERE id=?", id);
        mysql.update("DELETE FROM demo_orders WHERE order_id=?", id);
        pg.update("DELETE FROM demo_receipts WHERE event_id=?", id);
    }

    @Test
    void repeatedCallbackHasOneDurableReceipt() {
        service.recordPayment(p);
        assertEquals("ACK", receipts.receive("NOTIFY", p));
        assertEquals("ACK", receipts.receive("NOTIFY", p));
        assertEquals(1, pg.queryForObject("SELECT COUNT(*) FROM demo_receipts WHERE event_id=?", Integer.class, id));
    }

    @Test
    void localFailureRollsBackPaymentAndNotificationTogether() {
        var failed = new Purchase(id, "U1001", "SKU-001", 1, new BigDecimal("10.00"), Purchase.Failure.AFTER_ORDER);
        assertThrows(BusinessException.class, () -> service.recordPayment(failed));
        assertEquals(0, mysql.queryForObject("SELECT COUNT(*) FROM demo_orders WHERE order_id=?", Integer.class, id));
        assertEquals(0, mysql.queryForObject("SELECT COUNT(*) FROM demo_tasks WHERE id=?", Integer.class, id));
    }
}

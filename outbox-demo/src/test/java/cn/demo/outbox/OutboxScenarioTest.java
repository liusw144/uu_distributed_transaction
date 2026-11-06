package cn.demo.outbox;
import cn.demo.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DEMO_DB_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="demo.worker.enabled=false")
class OutboxScenarioTest {
    @Autowired OutboxService service;@Autowired ReceiptService receipts;@Autowired TaskStore tasks;
    @Autowired BusinessRepository business;@Autowired @Qualifier("mysqlJdbc") JdbcTemplate mysql;
    @Autowired @Qualifier("pgJdbc") JdbcTemplate pg;String id;
    @BeforeEach void setup() {
        id="t-"+UUID.randomUUID();
        mysql.update("INSERT INTO demo_stock(id,available) VALUES (?,10)","OUTBOX:"+id);
        pg.update("INSERT INTO demo_accounts(id,balance) VALUES (?,100.00)","OUTBOX:"+id);
    }
    Purchase p(int quantity,Purchase.Failure failure) { return new Purchase(id,id,id,quantity,new BigDecimal("10.00"),failure); }
    @AfterEach void cleanup() {
        mysql.update("DELETE FROM demo_tasks WHERE id=?",id);mysql.update("DELETE FROM demo_orders WHERE order_id=?",id);
        mysql.update("DELETE FROM demo_stock WHERE id=?","OUTBOX:"+id);
        pg.update("DELETE FROM demo_receipts WHERE event_id=?",id);pg.update("DELETE FROM demo_accounts WHERE id=?","OUTBOX:"+id);
    }
    @Test void duplicateDeliveryAndLostAckDebitOnlyOnce() {
        var request=p(2,Purchase.Failure.NONE);service.purchase(request);
        assertEquals("PAID",receipts.receive("OUTBOX",request));
        // 模拟第一次 HTTP 响应丢失，发送端以完全相同事件再投递。
        assertEquals("PAID",receipts.receive("OUTBOX",request));
        assertEquals(new BigDecimal("80.00"),pg.queryForObject("SELECT balance FROM demo_accounts WHERE id=?",BigDecimal.class,"OUTBOX:"+id));
        assertThrows(BusinessException.class,()->receipts.receive("OUTBOX",p(3,Purchase.Failure.NONE)));
    }
    @Test void rejectedPaymentReleasesInventoryExactlyOnce() {
        pg.update("UPDATE demo_accounts SET balance=0 WHERE id=?","OUTBOX:"+id);
        var request=p(2,Purchase.Failure.NONE);service.purchase(request);
        assertEquals("REJECTED",receipts.receive("OUTBOX",request));
        var task=tasks.claim("OUTBOX").orElseThrow();
        assertEquals(id,task.id());
        assertTrue(tasks.finish(task,"REJECTED",()->business.finishOutbox(request,"REJECTED")));
        assertFalse(tasks.finish(task,"REJECTED",()->business.finishOutbox(request,"REJECTED")));
        assertEquals(10,mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?",Integer.class,"OUTBOX:"+id));
        assertEquals("CANCELLED",mysql.queryForObject("SELECT status FROM demo_orders WHERE order_id=?",String.class,id));
    }
    @Test void exceptionBeforeLocalCommitLeavesNoOrderTaskOrReservation() {
        assertThrows(BusinessException.class,()->service.purchase(p(2,Purchase.Failure.AFTER_ACCOUNT)));
        assertEquals(0,mysql.queryForObject("SELECT COUNT(*) FROM demo_orders WHERE order_id=?",Integer.class,id));
        assertEquals(0,mysql.queryForObject("SELECT COUNT(*) FROM demo_tasks WHERE id=?",Integer.class,id));
        assertEquals(10,mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?",Integer.class,"OUTBOX:"+id));
    }
}

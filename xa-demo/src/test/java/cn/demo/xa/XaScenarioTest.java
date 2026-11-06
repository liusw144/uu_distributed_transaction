package cn.demo.xa;
import cn.demo.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DEMO_XA_TESTS",matches="true")
@SpringBootTest(classes=XaApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
        properties={"atomikos.properties.log-base-dir=target/xa-test-logs","atomikos.properties.transaction-manager-unique-name=dtx-xa-test"})
class XaScenarioTest {
    @Autowired XaService service;@Autowired @Qualifier("mysqlJdbc") JdbcTemplate mysql;@Autowired @Qualifier("pgJdbc") JdbcTemplate pg;
    String id;
    @BeforeEach void setup() {
        id="x-"+UUID.randomUUID();mysql.update("INSERT INTO demo_stock(id,available) VALUES (?,10)","XA:"+id);
        pg.update("INSERT INTO demo_accounts(id,balance) VALUES (?,100.00)","XA:"+id);
    }
    @AfterEach void cleanup() {
        mysql.update("DELETE FROM demo_orders WHERE order_id=?",id);mysql.update("DELETE FROM demo_stock WHERE id=?","XA:"+id);
        pg.update("DELETE FROM demo_accounts WHERE id=?","XA:"+id);
    }
    Purchase p(Purchase.Failure failure) { return new Purchase(id,id,id,2,new BigDecimal("10.00"),failure); }
    @Test void commitsBothRealXaResources() {
        service.purchase(p(Purchase.Failure.NONE));
        assertEquals("PAID",mysql.queryForObject("SELECT status FROM demo_orders WHERE order_id=?",String.class,id));
        assertEquals(8,mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?",Integer.class,"XA:"+id));
        assertEquals(new BigDecimal("80.00"),pg.queryForObject("SELECT balance FROM demo_accounts WHERE id=?",BigDecimal.class,"XA:"+id));
    }
    @Test void failureAfterDebitRollsBackBothDatabases() {
        assertThrows(BusinessException.class,()->service.purchase(p(Purchase.Failure.AFTER_ACCOUNT)));
        assertEquals(0,mysql.queryForObject("SELECT COUNT(*) FROM demo_orders WHERE order_id=?",Integer.class,id));
        assertEquals(10,mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?",Integer.class,"XA:"+id));
        assertEquals(new BigDecimal("100.00"),pg.queryForObject("SELECT balance FROM demo_accounts WHERE id=?",BigDecimal.class,"XA:"+id));
    }
}

package cn.demo.seata.at;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import cn.demo.common.BusinessException;
import cn.demo.common.Purchase;
import io.seata.core.context.RootContext;
import io.seata.rm.datasource.DataSourceProxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 真正连接本机 TC 与两个数据库；用原始连接读取，避免代理缓存掩盖结果。
 *
 * @author liusw144
 */
@EnabledIfEnvironmentVariable(named = "DEMO_SEATA_TESTS", matches = "true")
@SpringBootTest(classes = AtApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AtIntegrationTest {

    @Autowired
    AtOrderService service;

    @Autowired
    @Qualifier("rawMysql")
    DataSource rawMysql;

    @Autowired
    @Qualifier("rawPg")
    DataSource rawPg;

    @Autowired
    @Qualifier("mysqlDataSource")
    DataSource mysqlProxy;

    @Autowired
    @Qualifier("pgDataSource")
    DataSource pgProxy;

    JdbcTemplate mysql, pg;

    String id;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("sql/mysql.sql")).execute(rawMysql);
        new ResourceDatabasePopulator(new ClassPathResource("sql/postgresql.sql")).execute(rawPg);
        mysql = new JdbcTemplate(rawMysql);
        pg = new JdbcTemplate(rawPg);
        id = "at-" + UUID.randomUUID();
        mysql.update("INSERT INTO demo_stock(id,available) VALUES (?,10)", "SEATA_AT:" + id);
        pg.update("INSERT INTO demo_accounts(id,balance) VALUES (?,100.00)", "SEATA_AT:" + id);
    }

    @AfterEach
    void cleanup() {
        // 每个测试独立资源，不修改用户手工演示的数据。
        mysql.update("DELETE FROM demo_orders WHERE order_id=?", id);
        mysql.update("DELETE FROM demo_stock WHERE id=?", "SEATA_AT:" + id);
        pg.update("DELETE FROM demo_accounts WHERE id=?", "SEATA_AT:" + id);
        assertNull(RootContext.getXID(), "调用结束后线程上下文必须清理");
    }

    Purchase purchase(Purchase.Failure failure) {
        return new Purchase(id, id, id, 2, new BigDecimal("10.00"), failure);
    }

    void assertResources(int stock, String balance, int orders) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertEquals(stock, mysql.queryForObject("SELECT available FROM demo_stock WHERE id=?", Integer.class,
                    "SEATA_AT:" + id));
            assertEquals(new BigDecimal(balance), pg.queryForObject("SELECT balance FROM demo_accounts WHERE id=?",
                    BigDecimal.class, "SEATA_AT:" + id));
            assertEquals(orders,
                    mysql.queryForObject("SELECT COUNT(*) FROM demo_orders WHERE order_id=?", Integer.class, id));
        });
    }

    @Test
    void actualSeataProxiesWrapBothResources() {
        assertInstanceOf(DataSourceProxy.class, mysqlProxy);
        assertInstanceOf(DataSourceProxy.class, pgProxy);
    }

    @Test
    void commitsBothResourcesUsingRealCoordinator() {
        String xid = service.purchase(purchase(Purchase.Failure.NONE));
        // TC 可能以本机网卡地址生成 XID；连接地址为 localhost 不代表 XID 也必须是回环地址。
        assertNotNull(xid);
        assertTrue(xid.matches(".+:[0-9]+:[0-9]+"), xid);
        assertResources(8, "80.00", 1);
        assertEquals("PAID", mysql.queryForObject("SELECT status FROM demo_orders WHERE order_id=?", String.class, id));
    }

    @ParameterizedTest
    @EnumSource(value = Purchase.Failure.class, names = {"AFTER_ORDER", "AFTER_STOCK", "AFTER_ACCOUNT"})
    void failureRollsBackEveryParticipatingDatabase(Purchase.Failure failure) {
        // Seata 2.0 的 AdapterInvocationWrapper 会包装业务异常，仍须核对真正根因。
        assertThatThrownBy(() -> service.purchase(purchase(failure))).hasRootCauseInstanceOf(BusinessException.class);
        assertResources(10, "100.00", 0);
    }

    @Test
    void accountShortageRollsBackPreviouslyPreparedMysql() {
        pg.update("UPDATE demo_accounts SET balance=1.00 WHERE id=?", "SEATA_AT:" + id);
        assertThatThrownBy(() -> service.purchase(purchase(Purchase.Failure.NONE)))
                .hasRootCauseInstanceOf(BusinessException.class);
        assertResources(10, "1.00", 0);
    }

    @Test
    void stockShortageCannotCreateOrderOrDebitAccount() {
        mysql.update("UPDATE demo_stock SET available=1 WHERE id=?", "SEATA_AT:" + id);
        assertThatThrownBy(() -> service.purchase(purchase(Purchase.Failure.NONE)))
                .hasRootCauseInstanceOf(BusinessException.class);
        assertResources(1, "100.00", 0);
    }

    @Test
    void repeatedBusinessIdCannotChargeAgainOrRollBackEarlierSuccess() {
        service.purchase(purchase(Purchase.Failure.NONE));
        assertThrows(Exception.class, () -> service.purchase(purchase(Purchase.Failure.NONE)));
        assertResources(8, "80.00", 1);
        Purchase conflict = new Purchase(id, id, id, 3, new BigDecimal("10.00"), Purchase.Failure.NONE);
        assertThrows(Exception.class, () -> service.purchase(conflict));
        assertResources(8, "80.00", 1);
    }
}

package cn.demo.seata.tcc;

import cn.demo.common.Purchase;
import cn.demo.common.BusinessException;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DEMO_SEATA_TESTS", matches = "true")
class TccIntegrationTest {
    @Autowired
    TccService service;
    @Autowired
    TccStore store;
    @Autowired
    OrderAction order;
    @Autowired
    AccountAction account;
    @Autowired
    PhaseFaults faults;
    @Autowired
    @Qualifier("mysqlJdbc")
    JdbcTemplate mysql;
    @Autowired
    @Qualifier("pgJdbc")
    JdbcTemplate pg;
    String user, product;

    @BeforeEach
    void seed() {
        String id = UUID.randomUUID().toString().replace("-", "");
        user = "u_" + id;
        product = "p_" + id;
        mysql.update("INSERT INTO seata_tcc_stock(product_id,available,frozen) VALUES (?,20,0)", product);
        pg.update("INSERT INTO seata_tcc_account(user_id,available,frozen) VALUES (?,1000,0)", user);
    }

    Purchase purchase(Purchase.Failure fail) {
        return new Purchase("o_" + UUID.randomUUID().toString().replace("-", ""), user, product, 2, new BigDecimal("10.00"), fail);
    }

    void await(Runnable assertions) {
        long until = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        AssertionError last = null;
        do {
            try {
                assertions.run();
                return;
            } catch (AssertionError e) {
                last = e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } while (System.nanoTime() < until);
        throw last;
    }

    void resources(int stock, String balance) {
        assertThat(mysql.queryForObject("SELECT available FROM seata_tcc_stock WHERE product_id=?", Integer.class, product)).isEqualTo(stock);
        assertThat(mysql.queryForObject("SELECT frozen FROM seata_tcc_stock WHERE product_id=?", Integer.class, product)).isZero();
        assertThat(pg.queryForObject("SELECT available FROM seata_tcc_account WHERE user_id=?", BigDecimal.class, user)).isEqualByComparingTo(balance);
        assertThat(pg.queryForObject("SELECT frozen FROM seata_tcc_account WHERE user_id=?", BigDecimal.class, user)).isEqualByComparingTo("0");
    }

    @Test
    void successAndBusinessReplay() {
        var p = purchase(Purchase.Failure.NONE);
        service.purchase(p);
        await(() -> resources(18, "980"));
        service.purchase(p);
        resources(18, "980");
        assertThatThrownBy(() -> service.purchase(new Purchase(p.orderId(), user, product, 3, p.unitPrice(), p.failAt()))).rootCause().isInstanceOf(BusinessException.class).hasMessageContaining("内容冲突");
        var b = mysql.queryForMap("SELECT xid,branch_id FROM seata_tcc_order_branch WHERE order_id=?", p.orderId());
        var c = context(b.get("xid").toString(), ((Number) b.get("branch_id")).longValue());
        assertThat(order.confirm(c)).isTrue();
        assertThat(order.cancel(c)).isFalse();
        var ab = pg.queryForMap("SELECT xid,branch_id,state FROM seata_tcc_account_branch WHERE order_id=?", p.orderId());
        assertThat(ab.get("state")).isEqualTo("CONFIRMED");
        var ac = context(ab.get("xid").toString(), ((Number) ab.get("branch_id")).longValue());
        assertThat(account.confirm(ac)).isTrue();
        assertThat(account.cancel(ac)).isFalse();
        resources(18, "980");
    }

    @Test
    void allFailurePointsCancel() {
        for (var failure : new Purchase.Failure[]{Purchase.Failure.AFTER_ORDER, Purchase.Failure.AFTER_STOCK, Purchase.Failure.AFTER_ACCOUNT}) {
            assertThatThrownBy(() -> service.purchase(purchase(failure))).hasRootCauseInstanceOf(BusinessException.class);
            await(() -> resources(20, "1000"));
        }
    }

    @Test
    void insufficientAccountReleasesStock() {
        pg.update("UPDATE seata_tcc_account SET available=1 WHERE user_id=?", user);
        assertThatThrownBy(() -> service.purchase(purchase(Purchase.Failure.NONE))).hasRootCauseInstanceOf(BusinessException.class);
        await(() -> resources(20, "1"));
    }

    @Test
    void insufficientStock() {
        mysql.update("UPDATE seata_tcc_stock SET available=1 WHERE product_id=?", product);
        assertThatThrownBy(() -> service.purchase(purchase(Purchase.Failure.NONE))).hasRootCauseInstanceOf(BusinessException.class);
        await(() -> resources(1, "1000"));
    }

    @Test
    void emptyCancelBlocksLateTryAndRepeatedCancel() {
        var p = purchase(Purchase.Failure.NONE);
        var c = context("empty_" + p.orderId(), 123);
        assertThat(order.cancel(c)).isTrue();
        assertThat(order.cancel(c)).isTrue();
        assertThatThrownBy(() -> store.tryOrder(c, p)).hasMessageContaining("晚到");
        assertThat(account.cancel(c)).isTrue();
        assertThat(account.cancel(c)).isTrue();
        assertThatThrownBy(() -> store.tryAccount(c, p)).hasMessageContaining("晚到");
        resources(20, "1000");
    }

    @Test
    void tcRetriesFailedConfirm() {
        var p = purchase(Purchase.Failure.NONE);
        faults.arm(p.orderId(), "CONFIRM", 2);
        service.purchase(p);
        await(() -> resources(18, "980"));
        assertThat(faults.remaining(p.orderId(), "CONFIRM")).isZero();
    }

    @Test
    void tcRetriesFailedCancel() {
        var p = purchase(Purchase.Failure.AFTER_ACCOUNT);
        faults.arm(p.orderId(), "CANCEL", 2);
        assertThatThrownBy(() -> service.purchase(p)).hasRootCauseInstanceOf(BusinessException.class);
        await(() -> resources(20, "1000"));
        assertThat(faults.remaining(p.orderId(), "CANCEL")).isZero();
        var b = mysql.queryForMap("SELECT xid,branch_id FROM seata_tcc_order_branch WHERE order_id=?", p.orderId());
        assertThat(order.cancel(context(b.get("xid").toString(), ((Number) b.get("branch_id")).longValue()))).isTrue();
    }

    @Test
    void repeatedTryDoesNotFreezeTwiceAndConflictingContentFails() {
        var p = purchase(Purchase.Failure.NONE);
        var c = context("direct_" + p.orderId(), 456);
        assertThat(store.tryOrder(c, p)).isTrue();
        assertThat(store.tryOrder(c, p)).isTrue();
        assertThat(store.tryAccount(c, p)).isTrue();
        assertThat(store.tryAccount(c, p)).isTrue();
        assertThatThrownBy(() -> store.tryOrder(c, new Purchase(p.orderId(), user, product, 3, p.unitPrice(), p.failAt()))).hasMessageContaining("内容冲突");
        assertThatThrownBy(() -> store.tryAccount(c, new Purchase(p.orderId(), user, product, 3, p.unitPrice(), p.failAt()))).hasMessageContaining("内容冲突");
        assertThat(mysql.queryForObject("SELECT frozen FROM seata_tcc_stock WHERE product_id=?", Integer.class, product)).isEqualTo(2);
        assertThat(pg.queryForObject("SELECT frozen FROM seata_tcc_account WHERE user_id=?", BigDecimal.class, user)).isEqualByComparingTo("20");
        assertThat(order.cancel(c)).isTrue();
        assertThat(order.cancel(c)).isTrue();
        assertThat(account.cancel(c)).isTrue();
        assertThat(account.cancel(c)).isTrue();
        resources(20, "1000");
    }

    @Test
    void cancelledBusinessCannotBeExecutedAgain() {
        var p = purchase(Purchase.Failure.AFTER_ACCOUNT);
        assertThatThrownBy(() -> service.purchase(p)).hasRootCauseInstanceOf(BusinessException.class);
        await(() -> resources(20, "1000"));
        assertThatThrownBy(() -> service.purchase(new Purchase(p.orderId(), user, product, 2, p.unitPrice(), Purchase.Failure.NONE))).rootCause().isInstanceOf(BusinessException.class).hasMessageContaining("已占用");
        assertThatThrownBy(() -> service.purchase(new Purchase(p.orderId(), user, product, 3, p.unitPrice(), Purchase.Failure.NONE))).rootCause().isInstanceOf(BusinessException.class).hasMessageContaining("内容冲突");
        resources(20, "1000");
    }

    static BusinessActionContext context(String xid, long branch) {
        var c = new BusinessActionContext();
        c.setXid(xid);
        c.setBranchId(branch);
        return c;
    }
}


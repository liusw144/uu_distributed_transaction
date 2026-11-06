package cn.demo.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RetryPolicyTest {
    @Test void exponentialBackoffIsBoundedAndLastFailureIsDead() {
        var retry = new RetryPolicy(4, 2, 10);
        assertEquals(2, retry.delaySeconds(1));
        assertEquals(4, retry.delaySeconds(2));
        assertEquals(10, retry.delaySeconds(20));
        assertFalse(retry.exhausted(3));
        assertTrue(retry.exhausted(4));
    }
}

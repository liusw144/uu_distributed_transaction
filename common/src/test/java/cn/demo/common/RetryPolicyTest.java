package cn.demo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 验证RetryPolicy的事务行为与边界条件。
 *
 * @author liusw144
 */
class RetryPolicyTest {

    @Test
    void exponentialBackoffIsBoundedAndLastFailureIsDead() {
        var retry = new RetryPolicy(4, 2, 10);
        assertEquals(2, retry.delaySeconds(1));
        assertEquals(4, retry.delaySeconds(2));
        assertEquals(10, retry.delaySeconds(20));
        assertFalse(retry.exhausted(3));
        assertTrue(retry.exhausted(4));
    }
}

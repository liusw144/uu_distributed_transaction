package cn.demo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 验证Purchase的事务行为与边界条件。
 *
 * @author liusw144
 */
class PurchaseTest {

    @Test
    void calculatesExactMoneyWithoutFloatingPoint() {
        assertEquals(new BigDecimal("0.30"),
                new Purchase("o1", "U1001", "SKU-001", 3, new BigDecimal("0.10"), Purchase.Failure.NONE).amount());
    }

    @Test
    void rejectsNegativeQuantityAndFractionalCents() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertFalse(validator
                    .validate(new Purchase("o1", "U1001", "SKU-001", -1, new BigDecimal("1.00"), Purchase.Failure.NONE))
                    .isEmpty());
            assertFalse(validator
                    .validate(new Purchase("o1", "U1001", "SKU-001", 1, new BigDecimal("0.001"), Purchase.Failure.NONE))
                    .isEmpty());
        }
    }
}

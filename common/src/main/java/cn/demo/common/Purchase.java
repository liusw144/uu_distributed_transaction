package cn.demo.common;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** 金额由单价与数量精确计算；业务编号由调用方生成，重试必须复用同一编号。 */
public record Purchase(
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String orderId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,40}") String userId,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,40}") String productId,
        @Min(1) @Max(1000) int quantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer=7,fraction=2) BigDecimal unitPrice,
        Failure failAt) {
    public enum Failure { NONE, AFTER_ORDER, AFTER_STOCK, AFTER_ACCOUNT }
    public Purchase { if (failAt == null) failAt = Failure.NONE; }
    public BigDecimal amount() { return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2); }
    public void failIf(Failure point) {
        if (failAt == point) throw new BusinessException("模拟故障：" + point);
    }
    public boolean sameBusiness(Purchase other) {
        return orderId.equals(other.orderId) && userId.equals(other.userId)
                && productId.equals(other.productId) && quantity == other.quantity
                && unitPrice.compareTo(other.unitPrice) == 0;
    }
}

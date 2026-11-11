package cn.demo.common;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * 表示采购请求，金额由单价与数量精确计算，重试时必须复用调用方生成的业务编号。
 *
 * @author liusw144
 */
public record Purchase(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String userId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String productId, @Min(1) @Max(1000) int quantity,
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") @Digits(integer = 7, fraction = 2) BigDecimal unitPrice,
        Failure failAt) {
    /**
     * 定义订单、库存和账户操作后的故障注入位置。
     *
     * @author liusw144
     */
    public enum Failure {
        /** 不注入故障。 */
        NONE,
        /** 订单写入后失败。 */
        AFTER_ORDER,
        /** 库存处理后失败。 */
        AFTER_STOCK,
        /** 账户处理后失败。 */
        AFTER_ACCOUNT
    }

    public Purchase {
        if (failAt == null) {
            failAt = Failure.NONE;
        }
    }

    /** 返回保留两位小数的订单总金额。 */
    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2);
    }

    /** 当前操作位置命中故障配置时抛出业务异常。 */
    public void failIf(Failure point) {
        if (failAt == point) {
            throw new BusinessException("模拟故障：" + point);
        }
    }

    /** 比较请求的业务内容，忽略仅影响演示过程的故障配置。 */
    public boolean sameBusiness(Purchase other) {
        return orderId.equals(other.orderId) && userId.equals(other.userId) && productId.equals(other.productId)
                && quantity == other.quantity && unitPrice.compareTo(other.unitPrice) == 0;
    }
}

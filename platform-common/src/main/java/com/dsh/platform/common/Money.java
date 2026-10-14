package com.dsh.platform.common;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象（G2）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>⛔ 内部只用 {@link BigDecimal}，禁用 {@code double} / {@code float} ——
 *       浮点误差在资金系统里就是事故。</li>
 *   <li>统一 2 位小数，舍入模式 {@link RoundingMode#HALF_UP}。</li>
 *   <li>序列化为 <b>JSON 字符串</b>（{@link JsonValue}），⛔ 不是 number ——
 *       JS 的 Number 只有 53 位精度，大额金额会丢分。</li>
 * </ol>
 *
 * <p>用法：{@code Money.of("1000.00").subtract(Money.of("50"))} → {@code "950.00"}
 */
public final class Money implements Comparable<Money> {

    /** 金额小数位（分）。 */
    public static final int SCALE = 2;

    /** 零元。 */
    public static final Money ZERO = new Money(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));

    private final BigDecimal value;

    private Money(BigDecimal value) {
        this.value = value;
    }

    /**
     * 从字符串构造（推荐入口，⛔ 不要用 double 构造）。
     *
     * @param amount 形如 {@code "1000"} / {@code "1000.5"} / {@code "0.005"}
     * @return 已按 HALF_UP 归一到 2 位小数的金额
     */
    public static Money of(String amount) {
        Objects.requireNonNull(amount, "amount 不能为空");
        return new Money(new BigDecimal(amount.trim()).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** 从 BigDecimal 构造（用于数据库 DECIMAL 字段）。 */
    public static Money of(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount 不能为空");
        return new Money(amount.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** 从分（long）构造，用于渠道回调的「分」单位换算。 */
    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents).movePointLeft(2).setScale(SCALE, RoundingMode.HALF_UP));
    }

    public Money add(Money other) {
        return new Money(this.value.add(other.value).setScale(SCALE, RoundingMode.HALF_UP));
    }

    public Money subtract(Money other) {
        return new Money(this.value.subtract(other.value).setScale(SCALE, RoundingMode.HALF_UP));
    }

    public Money multiply(BigDecimal rate) {
        return new Money(this.value.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** 是否严格大于零。 */
    public boolean isPositive() {
        return value.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNegative() {
        return value.compareTo(BigDecimal.ZERO) < 0;
    }

    public boolean isZero() {
        return value.compareTo(BigDecimal.ZERO) == 0;
    }

    /** 原样输出（固定 2 位小数），如 {@code "1000.00"}。 */
    @JsonValue
    public String toPlainString() {
        return value.toPlainString();
    }

    /** 底层 BigDecimal（用于写库与 MathContext 精确运算）。 */
    public BigDecimal toBigDecimal() {
        return value;
    }

    /** 转为分，用于渠道接口。 */
    public long toCents() {
        return value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Override
    public int compareTo(Money o) {
        return this.value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money money)) {
            return false;
        }
        // 用 compareTo 而非 equals：Money.of("1.0") 与 Money.of("1.00") 应相等
        return this.value.compareTo(money.value) == 0;
    }

    @Override
    public int hashCode() {
        return value.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return toPlainString();
    }
}

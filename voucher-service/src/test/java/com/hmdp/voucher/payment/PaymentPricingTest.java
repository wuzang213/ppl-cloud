package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.domain.Voucher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PaymentPricing} 的纯逻辑单测。零外部依赖。
 *
 * <p>钉死的是本仓库的领域模型：<b>应付 = 券售价（pay_value）</b>，
 * 面值（actual_value）只是快照，<b>不是优惠、不参与计算</b>。
 * 如果哪天有人按「商品金额被券抵扣」的直觉把 amount 改成 {@code actual_value - pay_value}，
 * 这组断言会立刻失败 —— 这正是它存在的意义。
 */
class PaymentPricingTest {

    private static Voucher voucher(Long payValue, Long actualValue) {
        Voucher v = new Voucher();
        v.setId(99L);
        v.setPayValue(payValue);
        v.setActualValue(actualValue);
        return v;
    }

    @Test
    @DisplayName("应付 = pay_value（券售价 = 本次交易真正收的钱），面值 = actual_value（仅快照）")
    void price_usesPayValueAsAmount() {
        // 花 10 元买面值 100 元的券
        PaymentPricing.Pricing pricing = PaymentPricing.price(voucher(1000L, 10000L));
        assertEquals(1000L, pricing.amount(), "应付必须是券售价，不是面值、也不是差额");
        assertEquals(10000L, pricing.faceValue());
    }

    @Test
    @DisplayName("faceValue 与 amount 的差额不参与计算：面值调大不影响应付")
    void price_faceValueDoesNotAffectAmount() {
        assertEquals(1000L, PaymentPricing.price(voucher(1000L, 100000L)).amount());
        assertEquals(1000L, PaymentPricing.price(voucher(1000L, 1000L)).amount());
    }

    @Test
    @DisplayName("券不存在 → 400")
    void price_voucherMissing() {
        assertThrows(BadRequestException.class, () -> PaymentPricing.price(null));
    }

    @Test
    @DisplayName("金额字段缺失或为负 → 券数据坏了（500，不是用户的错）")
    void price_invalidAmount() {
        assertThrows(BizIllegalException.class, () -> PaymentPricing.price(voucher(null, 10000L)));
        assertThrows(BizIllegalException.class, () -> PaymentPricing.price(voucher(1000L, null)));
        assertThrows(BizIllegalException.class, () -> PaymentPricing.price(voucher(-1L, 10000L)));
        assertThrows(BizIllegalException.class, () -> PaymentPricing.price(voucher(1000L, -1L)));
    }

    @Test
    @DisplayName("允许 0 元券（免费领券场景不应报错）")
    void price_zeroPayValueAllowed() {
        PaymentPricing.Pricing pricing = PaymentPricing.price(voucher(0L, 5000L));
        assertEquals(0L, pricing.amount());
        assertEquals(5000L, pricing.faceValue());
    }
}

package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.ForbiddenException;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.support.PaymentTestHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 归属校验（零外部依赖）。
 *
 * <p>「拿到别人的订单 id 就能替别人发起支付 / 查别人的支付单」是典型的越权。
 * 这里同时钉住<b>异常类型</b>：必须是 {@link ForbiddenException}（403），
 * 不能退化成 400 或 500 —— 否则前端无法区分「参数错」与「没权限」。
 */
class PaymentOwnershipTest {

    private static final long OWNER = 7L;
    private static final long OTHER = 999L;
    private static final long ORDER_ID = 10001L;

    @Test
    @DisplayName("他人订单不可 prepay → 403")
    void cannotPrepayOthersOrder() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, OWNER);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, OTHER));
        assertEquals(403, e.getCode());
        assertEquals(0, h.paymentMapper.insertCount, "越权请求不得留下任何支付单");
    }

    @Test
    @DisplayName("他人订单不可查支付单 → 403")
    void cannotQueryOthersPayment() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, OWNER);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, OWNER);

        ForbiddenException e = assertThrows(ForbiddenException.class,
                () -> h.service.query(ORDER_ID, OTHER));
        assertEquals(403, e.getCode());
    }

    @Test
    @DisplayName("本人订单未发起支付时查支付单 → 返回 null（存量订单没有支付单是正常情况）")
    void queryWithoutPaymentReturnsNull() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, OWNER);

        assertNull(h.service.query(ORDER_ID, OWNER), "订单存在但无支付单不是错误");
    }

    @Test
    @DisplayName("本人已发起支付时能查到支付单")
    void queryOwnPayment() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, OWNER);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, OWNER);

        Payment payment = h.service.query(ORDER_ID, OWNER);

        assertNotNull(payment);
        assertEquals(ORDER_ID, payment.getOrderId());
        assertEquals(OWNER, payment.getUserId());
    }

    @Test
    @DisplayName("订单不存在 → 400（查支付单同样如此）")
    void orderNotFoundIsBadRequest() {
        PaymentTestHarness h = new PaymentTestHarness();

        assertThrows(BadRequestException.class, () -> h.service.query(404L, OWNER));
        assertThrows(BadRequestException.class,
                () -> h.service.prepay(404L, PaymentTestHarness.CHANNEL_CODE, OWNER));
    }
}

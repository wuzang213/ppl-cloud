package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.common.exception.ForbiddenException;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.hmdp.voucher.payment.support.PaymentTestHarness;
import com.hmdp.voucher.payment.support.RecordingChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预下单的幂等与复用规则（零外部依赖）。
 *
 * <p>钉死三件事：
 * <ol>
 *   <li>重复 prepay <b>不新建支付单</b>（{@code uk_order_id} 的业务语义）；</li>
 *   <li>未过期时<b>连支付串一起复用</b>，不重复打扰渠道 —— 顺带回避「同一商户单号重复预下单」
 *       在部分渠道会报错的不确定性；</li>
 *   <li>已过期时<b>原地轮换</b>商户单号，而不是新插一行（插新行必然撞唯一键），
 *       也不是直接拒绝（那会让用户在 5~15 分钟窗口内完全无法支付）。</li>
 * </ol>
 */
class PrepayReuseTest {

    private static final long USER_ID = 7L;
    private static final long ORDER_ID = 10001L;

    @Test
    @DisplayName("首次 prepay：落一行支付单，金额取券售价、面值取券面值（单位分）")
    void firstPrepayCreatesOnePayment() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);

        PrepayResult result = h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);

        assertNotNull(result);
        assertEquals(1, h.paymentMapper.size());
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);
        assertEquals(1000L, payment.getAmount(), "应付必须是券售价 pay_value");
        assertEquals(10000L, payment.getFaceValue(), "面值快照取 actual_value，不参与计算");
        assertEquals(0, payment.getStatus(), "新建后应为 PENDING");
        assertTrue(payment.getOutTradeNo().startsWith("PAY"), "商户单号格式：PAY<id>");
        assertEquals(1, h.channel.prepayCount);
    }

    @Test
    @DisplayName("重复 prepay（未过期）：同一个 out_trade_no、同一支付串、仍只有一行，且不再问渠道")
    void repeatPrepayReusesSameTradeNo() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);

        PrepayResult first = h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        String firstTradeNo = h.paymentMapper.selectByOrderId(ORDER_ID).getOutTradeNo();

        PrepayResult second = h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);

        assertEquals(firstTradeNo, h.paymentMapper.selectByOrderId(ORDER_ID).getOutTradeNo(),
                "两次 prepay 必须落在同一个商户单号上");
        assertEquals(first.getPayload(), second.getPayload(), "支付串应原样复用");
        assertEquals(1, h.paymentMapper.size(), "第二次不得新建支付单");
        assertEquals(1, h.paymentMapper.insertCount);
        assertEquals(0, h.paymentMapper.rotateCount, "未过期不应轮换");
        assertEquals(1, h.channel.prepayCount, "未过期时不应重复打扰渠道");
    }

    @Test
    @DisplayName("预下单过期后重复 prepay：原地轮换出新的 out_trade_no，行数仍为 1")
    void expiredPrepayRotatesTradeNo() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        String oldTradeNo = h.paymentMapper.selectByOrderId(ORDER_ID).getOutTradeNo();

        // 假 DB 时钟前进 6 分钟 > prepay-ttl-minutes(默认 5)
        h.paymentMapper.advanceDbClockMinutes(6);

        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);

        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);
        assertNotEquals(oldTradeNo, payment.getOutTradeNo(), "过期后必须换新的商户单号");
        assertEquals(1, h.paymentMapper.size(), "轮换是原地更新，不是插新行");
        assertEquals(1, h.paymentMapper.rotateCount);
        assertEquals(0, payment.getStatus());
        assertEquals(2, h.channel.prepayCount, "轮换后需要重新向渠道取支付串");
        assertTrue(payment.getOutTradeNo().startsWith("PAY"), "轮换后的单号同样要合规");
    }

    @Test
    @DisplayName("已支付订单不可再 prepay → 400")
    void paidOrderCannotPrepay() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.defaultVoucher();
        h.order(ORDER_ID, USER_ID, OrderStatus.PAID, 9001L);

        assertThrows(BadRequestException.class,
                () -> h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID));
    }

    @Test
    @DisplayName("已取消订单不可 prepay → 400")
    void canceledOrderCannotPrepay() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.defaultVoucher();
        h.order(ORDER_ID, USER_ID, OrderStatus.CANCELED, 9001L);

        assertThrows(BadRequestException.class,
                () -> h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID));
    }

    @Test
    @DisplayName("已有进行中的支付单时不允许换渠道（一单一支付单）")
    void switchingChannelIsRejected() {
        PaymentTestHarness h = new PaymentTestHarness(new RecordingChannel("alipay_page"));
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> h.service.prepay(ORDER_ID, "alipay_page", USER_ID));
        assertTrue(e.getMessage().contains("切换"), e.getMessage());
    }

    @Test
    @DisplayName("渠道未配凭据 → 「渠道未配置」（与「渠道不存在」区分开）")
    void unreadyChannelIsRejectedDistinctly() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.channel.withReady(false);

        BizIllegalException e = assertThrows(BizIllegalException.class,
                () -> h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID));
        assertTrue(e.getMessage().contains("未配置"), e.getMessage());
    }

    @Test
    @DisplayName("非法 channel → 400；不传 channel 时用默认渠道")
    void channelResolution() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);

        assertThrows(BadRequestException.class, () -> h.service.prepay(ORDER_ID, "paypal", USER_ID));

        // 缺省渠道（harness 已把 default-channel 指到 mock）
        PrepayResult result = h.service.prepay(ORDER_ID, null, USER_ID);
        assertNotNull(result);
        assertEquals(PaymentTestHarness.CHANNEL_CODE, result.getChannel());
    }

    @Test
    @DisplayName("他人订单不可 prepay → 403")
    void cannotPrepayOthersOrder() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);

        assertThrows(ForbiddenException.class,
                () -> h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, 999L));
    }

    @Test
    @DisplayName("订单不存在 → 400")
    void orderNotFound() {
        PaymentTestHarness h = new PaymentTestHarness();
        assertThrows(BadRequestException.class,
                () -> h.service.prepay(404L, PaymentTestHarness.CHANNEL_CODE, USER_ID));
    }
}

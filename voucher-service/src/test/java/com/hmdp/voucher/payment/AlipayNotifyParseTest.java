package com.hmdp.voucher.payment;

import com.hmdp.voucher.controller.PaymentNotifyController;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.notify.AlipayNotifyParser;
import com.hmdp.voucher.payment.notify.WechatNotifyParser;
import com.hmdp.voucher.payment.service.impl.PaymentNotifyServiceImpl;
import com.hmdp.voucher.payment.support.AlipayNotificationFixture;
import com.hmdp.voucher.payment.support.PaymentTestHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 支付宝回调<b>端到端（进程内）</b>单测：真实 SDK 验签 + 真实 Controller 应答 + 真实业务落地。
 *
 * <p>覆盖的是「<b>接口层</b>」这条缝：解析器在 {@link AlipayNotifyParseTest} 里验的是「签名对不对」，
 * 这里验的是「<b>应答语义</b>对不对」—— 也就是那个最贵的错误：
 * 异常路径错误地回了 {@code success}，渠道于是停推，等于<b>静默丢单</b>。
 *
 * <p>不起 Spring 容器（{@code @SpringBootTest} 会去 Nacos 拉配置必然失败），
 * 而是直接 {@code new PaymentNotifyController(...)} —— 这也是为什么 Controller 的两个方法
 * 都能用「普通参数」被直接调用（一个是 {@code Map}，一个是原始 body + 请求头）。
 */
class AlipayNotifyParseTest {

    private static final Long ORDER_ID = 20002L;
    private static final Long USER_ID = 7L;
    private static final String OUT_TRADE_NO = "PAY1001";

    private AlipayNotificationFixture fixture;
    private PaymentTestHarness harness;
    private PaymentNotifyController controller;

    /**
     * 解析器实际持有的那份配置。
     *
     * <p>必须显式保存引用：{@code fixture.properties()} 每次调用都 <b>new</b> 一个新对象，
     * 用例里若再调一次拿到的是另一份 —— 改了也不会影响解析器。
     */
    private PayProperties payProperties;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new AlipayNotificationFixture();
        harness = new PaymentTestHarness();
        payProperties = fixture.properties();
        // 微信解析器在本类用不到，给一份空配置（它的 parse 会直接拒绝，绝不会被调用到）
        controller = new PaymentNotifyController(
                new PaymentNotifyServiceImpl(
                        new AlipayNotifyParser(payProperties),
                        new WechatNotifyParser(new PayProperties()),
                        harness.service));
    }

    /** 造「未支付订单 + 待支付支付单（1000 分）」这一对前置数据 */
    private Payment givenUnpaidOrderWithPendingPayment() {
        harness.unpaidOrder(ORDER_ID, USER_ID);
        return harness.pendingPayment(ORDER_ID, USER_ID, OUT_TRADE_NO, 1000L, 10000L);
    }

    // ==================== 正路径 ====================

    @Test
    @DisplayName("合法回调：订单置已支付 + pay_type=2 + 写 ORDER_PAID outbox + 应答 success")
    void validNotifyAdvancesOrderAndAcksSuccess() throws Exception {
        Payment payment = givenUnpaidOrderWithPendingPayment();
        Map<String, String> notify = fixture.successNotify("10.00", OUT_TRADE_NO);

        assertEquals("success", controller.alipayNotify(notify));

        VoucherOrder order = harness.orderMapper.get(ORDER_ID);
        assertEquals(OrderStatus.PAID.getValue(), order.getStatus());
        assertEquals(2, order.getPayType(), "支付宝的 pay_type 必须是 2");
        assertNotNull(order.getPayTime(), "必须写 pay_time（应用墙钟，供前端展示）");
        assertEquals(1, harness.orderMapper.markPaidCount);

        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus());
        assertEquals("2026092622001400000000000001", payment.getTradeNo());
        assertNotNull(payment.getNotifyPayload(), "回调原文必须留档");
        assertTrue(payment.getNotifyPayload().contains("sign="), "留档里要有当时的签名");

        verify(harness.outboxWriter, times(1)).write(eq("VOUCHER_ORDER"), eq(ORDER_ID),
                eq("ORDER_PAID"), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("回调验签不依赖应用私钥：只配 appId + 支付宝公钥时也必须受理（T11 验收真实踩到）")
    void notifyDoesNotRequireMerchantPrivateKey() throws Exception {
        Payment payment = givenUnpaidOrderWithPendingPayment();

        // 模拟「支付宝公钥已填、应用私钥还没填」：发单条件不满足，但验签条件满足。
        // 这正是接入期的真实状态 —— 而且它曾经让**所有回调被静默拒绝**：
        // 端点回 200 但支付单永远停在 PENDING，日志里只有一句「凭据未配置」。
        payProperties.getAlipay().setMerchantPrivateKey(null);
        assertFalse(payProperties.getAlipay().isConfigured(), "前置：发单条件应已不满足");
        assertTrue(payProperties.getAlipay().isNotifyConfigured(), "前置：验签条件应仍满足");

        Map<String, String> notify = fixture.successNotify("10.00", OUT_TRADE_NO);
        assertEquals("success", controller.alipayNotify(notify),
                "缺应用私钥不应导致回调被拒 —— 私钥是发单用的，回调是支付宝签给我们的");
        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus());
        assertEquals(OrderStatus.PAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
    }

    @Test
    @DisplayName("重复回调 3 次：只推进一次、只写一条 outbox，但每次都应答 success（让渠道停推）")
    void repeatNotifyIsIdempotentAndAcksSuccess() throws Exception {
        givenUnpaidOrderWithPendingPayment();

        for (int i = 0; i < 3; i++) {
            assertEquals("success", controller.alipayNotify(fixture.successNotify("10.00", OUT_TRADE_NO)));
        }

        assertEquals(1, harness.orderMapper.markPaidCount, "订单只能被推进一次（防重复积分）");
        assertEquals(1, harness.paymentMapper.advanceCount, "支付单只能被推进一次");
        verify(harness.outboxWriter, times(1)).write(anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("WAIT_BUYER_PAY：验签通过、什么都不改，应答 success（没有可做的事，不需要重推）")
    void waitBuyerPayDoesNotAdvanceButAcks() throws Exception {
        Payment payment = givenUnpaidOrderWithPendingPayment();
        Map<String, String> notify = fixture.notify("10.00", OUT_TRADE_NO, "WAIT_BUYER_PAY");

        assertEquals("success", controller.alipayNotify(notify));

        assertEquals(OrderStatus.UNPAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(0, harness.orderMapper.markPaidCount);
        verify(harness.outboxWriter, never()).write(anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    // ==================== 拒绝路径（应答必须是 failure，否则渠道停推 = 静默丢单） ====================

    @Test
    @DisplayName("金额被篡改：应答 failure 且零副作用（订单不动、outbox 不写）")
    void tamperedAmountIsRejectedWithoutSideEffects() throws Exception {
        Payment payment = givenUnpaidOrderWithPendingPayment();
        Map<String, String> notify = fixture.successNotify("10.00", OUT_TRADE_NO);
        notify.put("total_amount", "0.01");

        assertEquals("failure", controller.alipayNotify(notify));

        assertEquals(OrderStatus.UNPAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(0, harness.orderMapper.markPaidCount);
        assertEquals(0, harness.paymentMapper.advanceCount);
        verify(harness.outboxWriter, never()).write(anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("app_id 不是我们的：应答 failure 且零副作用")
    void appIdMismatchIsRejectedWithoutSideEffects() throws Exception {
        Payment payment = givenUnpaidOrderWithPendingPayment();
        Map<String, String> notify = fixture.successNotify("10.00", OUT_TRADE_NO);
        notify.put("app_id", "9999999999999999");
        fixture.sign(notify, "UTF-8");

        assertEquals("failure", controller.alipayNotify(notify));

        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(0, harness.orderMapper.markPaidCount);
    }

    @Test
    @DisplayName("订单已被超时关单后到达成功回调：拒绝推进 + 应答 failure + 留存原文（资损场景）")
    void notifyAfterOrderClosedIsRejectedAndArchived() throws Exception {
        harness.defaultVoucher();
        harness.order(ORDER_ID, USER_ID, OrderStatus.CANCELED, 9001L);
        Payment payment = harness.pendingPayment(ORDER_ID, USER_ID, OUT_TRADE_NO, 1000L, 10000L);

        assertEquals("failure", controller.alipayNotify(fixture.successNotify("10.00", OUT_TRADE_NO)));

        assertEquals(OrderStatus.CANCELED.getValue(), harness.orderMapper.get(ORDER_ID).getStatus(),
                "已关单的订单绝不能被回调复活");
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus(), "支付单也不能被推进");
        assertEquals(0, harness.orderMapper.markPaidCount);
        assertNotNull(payment.getNotifyPayload(), "资损场景必须留存原文，供人工对账");
        verify(harness.outboxWriter, never()).write(anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("商户单号反查不到支付单：应答 failure（让渠道重推，可能是我们事务还没提交）")
    void unknownOutTradeNoIsRejected() throws Exception {
        harness.unpaidOrder(ORDER_ID, USER_ID);
        // 刻意不建支付单：模拟「回调早于本地提交到达」这类竞态

        assertEquals("failure", controller.alipayNotify(fixture.successNotify("10.00", OUT_TRADE_NO)));

        assertEquals(OrderStatus.UNPAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
        assertNull(harness.paymentMapper.selectByOutTradeNo(OUT_TRADE_NO));
    }

    @Test
    @DisplayName("验签不过时绝不触碰数据库：空报文也要应答 failure")
    void unparsableNotifyDoesNotTouchDatabase() {
        Payment payment = givenUnpaidOrderWithPendingPayment();

        assertEquals("failure", controller.alipayNotify(Map.of()));
        assertEquals("failure", controller.alipayNotify(Map.of("out_trade_no", OUT_TRADE_NO)));

        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(0, harness.paymentMapper.saveNotifyCount);
        assertEquals(0, harness.paymentMapper.advanceCount);
    }
}

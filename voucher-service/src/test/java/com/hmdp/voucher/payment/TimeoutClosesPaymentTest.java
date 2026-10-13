package com.hmdp.voucher.payment;

import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.support.PaymentTestHarness;
import com.hmdp.voucher.payment.task.PayQueryTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 超时关单的支付单处理 + 主动查单任务（{@link PayQueryTask}）单测。
 *
 * <p>这两件事必须一起测，因为它们是一对互补的兜底：
 * <ul>
 *   <li><b>关单</b>负责「把不再该付的关掉」——只关 PENDING，绝不能碰已成功的；</li>
 *   <li><b>查单</b>负责「把可能已付的推上去」——本环境没有公网域名，渠道永远回调不进来，
 *       它是唯一能把状态推到终态的通道。</li>
 * </ul>
 *
 * <p>关单与查单都共用 {@code IPaymentService.applyNotify} 这条落地路径，
 * 所以这里同时验证了「查单也不会漏掉金额校验与幂等」。
 */
class TimeoutClosesPaymentTest {

    private static final Long ORDER_ID = 20002L;
    private static final Long USER_ID = 7L;
    private static final String OUT_TRADE_NO = "PAY1001";

    private PaymentTestHarness harness;
    private PayQueryTask task;

    @BeforeEach
    void setUp() {
        harness = new PaymentTestHarness();
        task = new PayQueryTask(harness.paymentMapper, harness.registry,
                harness.payProperties, harness.service);
    }

    /** 造「未支付订单 + 停留够久的 PENDING 支付单」 */
    private Payment givenStalePendingPayment() {
        harness.unpaidOrder(ORDER_ID, USER_ID);
        Payment payment = harness.pendingPayment(ORDER_ID, USER_ID, OUT_TRADE_NO, 1000L, 10000L);
        // TTL 是 5 分钟，把 DB 时钟推 6 分钟 → 超过 TTL，应被扫到
        harness.paymentMapper.advanceDbClockMinutes(6);
        return payment;
    }

    // ==================== 关单侧 ====================

    @Test
    @DisplayName("关单：PENDING 支付单被置为 CLOSED")
    void closePaymentForOrderClosesPending() {
        Payment payment = givenStalePendingPayment();

        assertEquals(1, harness.service.closePaymentForOrder(ORDER_ID));

        assertEquals(PaymentStatus.CLOSED.getValue(), payment.getStatus());
        assertEquals(1, harness.paymentMapper.markClosedByOrderCount);
    }

    @Test
    @DisplayName("关单：已成功的支付单绝不能被覆盖（条件更新 WHERE status = 0 的守卫）")
    void closePaymentForOrderLeavesSuccessfulAlone() {
        Payment payment = givenStalePendingPayment();
        harness.service.applyNotify(harness.successNotify(payment));
        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus());

        assertEquals(0, harness.service.closePaymentForOrder(ORDER_ID),
                "没有 PENDING 单可关，必须返回 0");

        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus(),
                "已成功的支付单被关掉 = 用户付了钱却查不到支付记录");
        assertEquals("TRADE-" + OUT_TRADE_NO, payment.getTradeNo(), "交易号不能被抹掉");
        assertNotNull(payment.getNotifyPayload(), "回调原文不能被抹掉");
    }

    @Test
    @DisplayName("关单：该订单没有支付单时是空操作，不报错")
    void closePaymentForOrderIsNoopWithoutPayment() {
        harness.unpaidOrder(ORDER_ID, USER_ID);

        assertEquals(0, harness.service.closePaymentForOrder(ORDER_ID));
        assertEquals(0, harness.paymentMapper.markClosedByOrderCount);
    }

    // ==================== 主动查单侧 ====================

    @Test
    @DisplayName("查单成功：订单推进为已支付 + 写 ORDER_PAID outbox + 支付单置成功")
    void queryOnceAdvancesOnChannelSuccess() {
        Payment payment = givenStalePendingPayment();
        harness.channel.withQueryResult(PayQueryResult.success("TRADE-1", 1000L, "TRADE_SUCCESS"));

        assertEquals(1, task.queryOnce(), "应处理这一笔");
        assertEquals(1, harness.channel.queryCount, "必须真的问了渠道一次");

        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus());
        assertEquals("TRADE-1", payment.getTradeNo());
        assertEquals(OrderStatus.PAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
        assertEquals(1, harness.orderMapper.markPaidCount);
        assertTrue(payment.getNotifyPayload().startsWith("QUERY_TASK|"),
                "留档必须标清来源是主动查单，否则对账分不清这行是谁写的");
        verify(harness.outboxWriter, times(1)).write(eq("VOUCHER_ORDER"), eq(ORDER_ID),
                eq("ORDER_PAID"), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("查单发现仍未支付：什么都不改，且不写 outbox")
    void queryOnceDoesNothingWhenChannelStillPending() {
        Payment payment = givenStalePendingPayment();
        harness.channel.withQueryResult(PayQueryResult.pending("WAIT_BUYER_PAY"));

        assertEquals(1, task.queryOnce());

        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(OrderStatus.UNPAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
        verify(harness.outboxWriter, never()).write(anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("查单金额与支付单不一致：拒绝推进 + 留存原文（查单也不能凭未知金额确认收款）")
    void queryOnceRejectsAmountMismatch() {
        Payment payment = givenStalePendingPayment();
        harness.channel.withQueryResult(PayQueryResult.success("TRADE-1", 1L, "TRADE_SUCCESS"));

        assertEquals(1, task.queryOnce());

        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(OrderStatus.UNPAID.getValue(), harness.orderMapper.get(ORDER_ID).getStatus());
        assertEquals(0, harness.orderMapper.markPaidCount);
        assertEquals(1, harness.paymentMapper.saveNotifyCount, "资损场景必须留档");
        assertTrue(payment.getNotifyPayload().startsWith("QUERY_TASK|"));
        verify(harness.outboxWriter, never()).write(anyString(), any(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("查单：渠道未配凭据时跳过，不算处理，也不炸")
    void queryOnceSkipsNotReadyChannel() {
        Payment payment = givenStalePendingPayment();
        harness.channel.withReady(false);

        assertEquals(0, task.queryOnce());

        assertEquals(0, harness.channel.queryCount, "凭据没配就不该去问渠道");
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
    }

    @Test
    @DisplayName("查单：库里的 channel 是脏值 → 跳过该条，绝不抛异常影响整批")
    void queryOnceSkipsUnknownChannel() {
        harness.unpaidOrder(ORDER_ID, USER_ID);
        Payment dirty = new Payment()
                .setId(5000L).setOrderId(ORDER_ID).setUserId(USER_ID).setChannel("bogus_channel")
                .setAmount(1000L).setFaceValue(10000L)
                .setStatus(PaymentStatus.PENDING.getValue()).setOutTradeNo(OUT_TRADE_NO);
        harness.paymentMapper.put(dirty);
        harness.paymentMapper.advanceDbClockMinutes(6);

        assertEquals(0, task.queryOnce(), "脏数据不该被算作已处理");

        assertEquals(PaymentStatus.PENDING.getValue(), dirty.getStatus());
        assertEquals(0, harness.channel.queryCount);
    }

    @Test
    @DisplayName("查单：只扫停留超过 TTL 的单，刚发起的不打扰渠道")
    void queryOnceOnlyPicksStale() {
        harness.unpaidOrder(ORDER_ID, USER_ID);
        harness.pendingPayment(ORDER_ID, USER_ID, OUT_TRADE_NO, 1000L, 10000L);
        harness.channel.withQueryResult(PayQueryResult.success("TRADE-1", 1000L, "TRADE_SUCCESS"));

        // 时钟没推进 → 未超过 prepay-ttl（5min）
        assertEquals(0, task.queryOnce());
        assertEquals(0, harness.channel.queryCount, "刚下单就去查渠道会撞渠道侧的查单频率限制");
    }

    @Test
    @DisplayName("TTL 必须 ≤ 订单超时(15min)：否则会出现「支付单还有效、订单已被关」的资损窗口")
    void prepayTtlMustNotExceedOrderTimeout() {
        int prepayTtl = harness.payProperties.getPrepayTtlMinutes();

        assertTrue(prepayTtl >= 1, "TTL 至少 1 分钟，否则用户来不及付款");
        assertTrue(prepayTtl <= 15, "TTL 必须 ≤ 订单超时 15min，当前=" + prepayTtl);
    }
}

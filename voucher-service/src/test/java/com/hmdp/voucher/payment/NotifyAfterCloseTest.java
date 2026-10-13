package com.hmdp.voucher.payment;

import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.payment.domain.NotifyOutcome;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.support.PaymentTestHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 「关单后收到成功回调」的处置（零外部依赖）—— 本仓库最典型的<b>资损场景</b>。
 *
 * <p>现实里它一定会发生：用户打开收银台、订单在 15 分钟后被超时关闭、用户在第 16 分钟完成付款。
 * 此时钱已经在渠道侧扣了，但订单已被取消。三种可能做法：
 * <ul>
 *   <li>静默忽略 —— <b>最坏</b>：用户付了钱、系统毫无痕迹，只能靠投诉才发现；</li>
 *   <li>自动置为已支付 —— 也错：券库存已回补、可能已被别人买走；</li>
 *   <li>✅ 拒绝推进 + 告警 + 留存原文 + 人工介入 —— 本期做法（规格 §8 开放问题 5 的决策）。</li>
 * </ul>
 * 本测试钉死第三种：拒绝、而且<b>必须留下证据</b>（原文入库 + 不对外应答成功）。
 */
class NotifyAfterCloseTest {

    private static final long USER_ID = 7L;
    private static final long ORDER_ID = 10001L;

    @Test
    @DisplayName("支付单已被关单（CLOSED）后收到成功回调 → 拒绝推进，支付单保持 CLOSED 且留存原文")
    void closedPaymentRejectsSuccessNotify() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        // 模拟订单超时关单：订单转已取消 + 支付单转 CLOSED
        h.orderMapper.get(ORDER_ID).setStatus(OrderStatus.CANCELED.getValue());
        payment.setStatus(PaymentStatus.CLOSED.getValue());

        NotifyOutcome outcome = h.service.applyNotify(h.successNotify(payment));

        assertEquals(NotifyOutcome.PAYMENT_NOT_PENDING, outcome);
        assertEquals(PaymentStatus.CLOSED.getValue(), payment.getStatus(), "CLOSED 是终态，不得被回调改回成功");
        assertEquals(OrderStatus.CANCELED.getValue(), h.orderMapper.get(ORDER_ID).getStatus());
        assertEquals(0, h.paymentMapper.advanceCount);
        assertEquals(1, h.paymentMapper.saveNotifyCount, "被拒绝的回调原文必须留档（否则用户付了钱毫无痕迹）");
        verify(h.outboxWriter, times(0)).write(any(), any(), any(), any(), any(), any());
        assertTrue(outcome.isAlarm(), "该场景必须标记为需人工介入的告警");
    }

    @Test
    @DisplayName("订单已取消但支付单还是 PENDING（部分失败态）→ 同样拒绝推进 + 留档")
    void canceledOrderWithPendingPaymentIsRejected() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        // 只关了订单，支付单没来得及关（例如关单事务的两步之间被打断）
        h.orderMapper.get(ORDER_ID).setStatus(OrderStatus.CANCELED.getValue());

        NotifyOutcome outcome = h.service.applyNotify(h.successNotify(payment));

        assertEquals(NotifyOutcome.ORDER_NOT_PAYABLE, outcome);
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus(),
                "订单不可支付时不得把支付单置为成功");
        assertEquals(0, h.orderMapper.markPaidCount);
        assertEquals(1, h.paymentMapper.saveNotifyCount);
        verify(h.outboxWriter, times(0)).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("订单已核销（并发极端态）→ 拒绝推进")
    void usedOrderIsRejected() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        h.orderMapper.get(ORDER_ID).setStatus(OrderStatus.USED.getValue());

        assertEquals(NotifyOutcome.ORDER_NOT_PAYABLE, h.service.applyNotify(h.successNotify(payment)));
        assertEquals(0, h.orderMapper.markPaidCount);
    }

    @Test
    @DisplayName("拒绝推进的回调不应答成功（否则渠道停止重推 = 静默丢单）")
    void rejectedOutcomeIsNotAckSuccess() {
        assertTrue(!NotifyOutcome.PAYMENT_NOT_PENDING.isAckSuccess());
        assertTrue(!NotifyOutcome.ORDER_NOT_PAYABLE.isAckSuccess());
        assertTrue(!NotifyOutcome.AMOUNT_MISMATCH.isAckSuccess());
        assertTrue(!NotifyOutcome.PAYMENT_NOT_FOUND.isAckSuccess());
        // 幂等与「未支付成功」必须应答成功，否则渠道会无意义地反复重推
        assertTrue(NotifyOutcome.APPLIED.isAckSuccess());
        assertTrue(NotifyOutcome.IDEMPOTENT.isAckSuccess());
        assertTrue(NotifyOutcome.NOT_SUCCESS.isAckSuccess());
    }
}

package com.hmdp.voucher.payment;

import com.hmdp.common.domain.OrderPaidMessage;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.payment.domain.NotifyOutcome;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;
import com.hmdp.voucher.payment.support.PaymentTestHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 支付结果落地的幂等（零外部依赖）。
 *
 * <p>核心断言是「<b>重放 N 次只推进 1 次</b>」：支付单条件更新的成功次数、
 * 订单推进次数、outbox 写入次数都必须是 1。这三者任一大于 1 都会造成真实损害 ——
 * 重复积分、重复发券、重复投递下游。
 */
class PaymentNotifyIdempotentTest {

    private static final long USER_ID = 7L;
    private static final long ORDER_ID = 10001L;

    @Test
    @DisplayName("首次成功回调：支付单转 SUCCESS、订单转已支付（pay_type=2）、outbox 写入一次")
    void firstSuccessNotifyAppliesEverything() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        NotifyOutcome outcome = h.service.applyNotify(h.successNotify(payment));

        assertEquals(NotifyOutcome.APPLIED, outcome);
        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus());
        assertNotNull(payment.getTradeNo(), "渠道交易号必须落库");
        assertNotNull(payment.getPayTime(), "pay_time 由应用写");
        assertNotNull(payment.getNotifyTime());
        assertNotNull(payment.getNotifyPayload(), "回调原文必须留存");

        VoucherOrder order = h.orderMapper.get(ORDER_ID);
        assertEquals(OrderStatus.PAID.getValue(), order.getStatus());
        assertEquals(2, order.getPayType(), "支付宝应记 pay_type=2");
        assertNotNull(order.getPayTime());

        verify(h.outboxWriter, times(1)).write(eq("VOUCHER_ORDER"), eq(ORDER_ID), eq("ORDER_PAID"),
                eq("seckill.direct"), eq("order.paid"), any(OrderPaidMessage.class));
    }

    @Test
    @DisplayName("成功回调重放 3 次：状态只推进 1 次，outbox 仍只 1 条")
    void replayIsIdempotent() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);
        PayNotifyCommand cmd = h.successNotify(payment);

        assertEquals(NotifyOutcome.APPLIED, h.service.applyNotify(cmd));
        assertEquals(NotifyOutcome.IDEMPOTENT, h.service.applyNotify(cmd));
        assertEquals(NotifyOutcome.IDEMPOTENT, h.service.applyNotify(cmd));

        assertEquals(1, h.paymentMapper.advanceCount, "支付单只能被推进一次");
        assertEquals(1, h.orderMapper.markPaidCount, "订单只能被推进一次");
        assertEquals(PaymentStatus.SUCCESS.getValue(), payment.getStatus());
        verify(h.outboxWriter, times(1)).write(any(), any(), any(), any(), any(), any(OrderPaidMessage.class));
    }

    @Test
    @DisplayName("回调金额与应付不一致 → 拒绝推进 + 留档，不改任何状态")
    void amountMismatchIsRejected() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        NotifyOutcome outcome = h.service.applyNotify(h.successNotify(payment, 1L));

        assertEquals(NotifyOutcome.AMOUNT_MISMATCH, outcome);
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus(), "拒绝推进时不得改状态");
        assertEquals(OrderStatus.UNPAID.getValue(), h.orderMapper.get(ORDER_ID).getStatus());
        assertEquals(0, h.paymentMapper.advanceCount);
        assertEquals(1, h.paymentMapper.saveNotifyCount, "拒绝的回调原文也必须留档");
        verify(h.outboxWriter, times(0)).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("回调金额缺失同样视为不一致（宁可疑也不按未知金额确认收款）")
    void missingAmountIsRejected() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        assertEquals(NotifyOutcome.AMOUNT_MISMATCH, h.service.applyNotify(h.successNotify(payment, null)));
        assertEquals(0, h.paymentMapper.advanceCount);
    }

    @Test
    @DisplayName("非成功通知（WAIT_BUYER_PAY）→ 不推进、不改状态")
    void waitBuyerPayDoesNotAdvance() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        NotifyOutcome outcome = h.service.applyNotify(h.notSuccessNotify(payment));

        assertEquals(NotifyOutcome.NOT_SUCCESS, outcome);
        assertEquals(PaymentStatus.PENDING.getValue(), payment.getStatus());
        assertEquals(0, h.paymentMapper.advanceCount);
    }

    @Test
    @DisplayName("未知商户单号 → PAYMENT_NOT_FOUND（且必须被当作告警，不允许静默吞掉）")
    void unknownOutTradeNoIsAlarm() {
        PaymentTestHarness h = new PaymentTestHarness();
        PayNotifyCommand cmd = new PayNotifyCommand()
                .setProvider(com.hmdp.voucher.payment.domain.PayProvider.ALIPAY)
                .setOutTradeNo("PAY-NOT-EXIST")
                .setTradeNo("T1")
                .setAmount(1000L)
                .setSuccess(true)
                .setRawStatus("TRADE_SUCCESS")
                .setPayload("{}");

        NotifyOutcome outcome = h.service.applyNotify(cmd);

        assertEquals(NotifyOutcome.PAYMENT_NOT_FOUND, outcome);
        assertEquals(0, h.paymentMapper.advanceCount);
        assertEquals(NotifyOutcome.PAYMENT_NOT_FOUND.isAlarm(), true, "未知单号属资损告警，必须会被看到");
    }

    @Test
    @DisplayName("缺少商户单号 → 不处理，也不抛异常炸掉回调接口")
    void missingOutTradeNoIsHandled() {
        PaymentTestHarness h = new PaymentTestHarness();
        PayNotifyCommand cmd = new PayNotifyCommand().setSuccess(true).setRawStatus("TRADE_SUCCESS");

        assertEquals(NotifyOutcome.PAYMENT_NOT_FOUND, h.service.applyNotify(cmd));
    }

    @Test
    @DisplayName("已 SUCCESS 的支付单再收成功通知 → 幂等，且旧原文不被覆盖")
    void alreadySuccessKeepsOldPayload() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        PayNotifyCommand first = h.successNotify(payment);
        h.service.applyNotify(first);
        String payloadAfterFirst = payment.getNotifyPayload();
        String tradeNoAfterFirst = payment.getTradeNo();

        PayNotifyCommand replay = h.successNotify(payment).setPayload("{\"replay\":true}");
        assertEquals(NotifyOutcome.IDEMPOTENT, h.service.applyNotify(replay));

        assertEquals(payloadAfterFirst, payment.getNotifyPayload(), "幂等路径不应改写已有原文");
        assertEquals(tradeNoAfterFirst, payment.getTradeNo(), "幂等路径不应改写交易号");
        assertEquals(1, h.paymentMapper.advanceCount);
    }

    @Test
    @DisplayName("微信渠道回调 → pay_type=3（支付方式由回调来源决定，不由下单渠道反推）")
    void wechatNotifyRecordsPayTypeThree() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        PayNotifyCommand cmd = h.successNotify(payment)
                .setProvider(com.hmdp.voucher.payment.domain.PayProvider.WECHAT)
                .setRawStatus("SUCCESS");

        assertEquals(NotifyOutcome.APPLIED, h.service.applyNotify(cmd));
        assertEquals(3, h.orderMapper.get(ORDER_ID).getPayType());
    }

    @Test
    @DisplayName("订单未被推进时不写 outbox（防重复积分）")
    void noOutboxWhenOrderNotAdvanced() {
        PaymentTestHarness h = new PaymentTestHarness();
        h.unpaidOrder(ORDER_ID, USER_ID);
        h.service.prepay(ORDER_ID, PaymentTestHarness.CHANNEL_CODE, USER_ID);
        Payment payment = h.paymentMapper.selectByOrderId(ORDER_ID);

        // 人为制造「订单已核销」的不一致态：支付成功但订单不可再推进
        h.orderMapper.get(ORDER_ID).setStatus(OrderStatus.USED.getValue());

        assertEquals(NotifyOutcome.ORDER_NOT_PAYABLE, h.service.applyNotify(h.successNotify(payment)));
        assertNull(payment.getTradeNo(), "拒绝推进时不应留下交易号");
        verify(h.outboxWriter, times(0)).write(any(), any(), any(), any(), any(), any());
    }
}

package com.hmdp.voucher.payment.channel.wechat;

import com.hmdp.voucher.payment.WechatTradeState;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import lombok.extern.slf4j.Slf4j;

/** 微信查单结果 → {@link PayQueryResult} 的唯一映射入口。Native 与 H5 两个渠道的查单返回同一个 {@code Transaction} 类型、
 * 状态语义也完全一致；各写一份迟早走偏（「Native 认 REFUND、H5 不认」这类），故抽成一处，由 {@link WechatTradeState} 提供判定。 */
@Slf4j
final class WechatQueryResults {

    private WechatQueryResults() {
    }

    static PayQueryResult of(Transaction tx, String outTradeNo) {
        if (tx == null) {
            return PayQueryResult.pending("NULL_RESPONSE");
        }
        Transaction.TradeStateEnum state = tx.getTradeState();
        String raw = state == null ? null : state.name();
        TransactionAmount txAmount = tx.getAmount();
        Integer total = txAmount == null ? null : txAmount.getTotal();
        Long amountFen = total == null ? null : total.longValue();

        if (WechatTradeState.isPaid(raw)) {
            return PayQueryResult.success(tx.getTransactionId(), amountFen, raw);
        }
        if (WechatTradeState.isClosed(raw)) {
            return PayQueryResult.failed(tx.getTransactionId(), amountFen, raw);
        }
        if (WechatTradeState.isAnomaly(raw)) {
            // 例如 REFUND：钱动过但既不能算成功也不能算关闭，必须留给人看
            log.error("微信交易状态异常，不推进也不关单（等人工介入）：outTradeNo={}, tradeState={}, tradeNo={}",
                    outTradeNo, raw, tx.getTransactionId());
            return PayQueryResult.pending("ANOMALY:" + raw);
        }
        // NOTPAY / USERPAYING / ACCEPT 等：用户还没付完，继续等
        return PayQueryResult.pending(raw);
    }
}

package com.hmdp.voucher.payment.service.impl;

import com.hmdp.voucher.payment.domain.NotifyAck;
import com.hmdp.voucher.payment.domain.NotifyOutcome;
import com.hmdp.voucher.payment.notify.AlipayNotifyParser;
import com.hmdp.voucher.payment.notify.WechatNotifyParser;
import com.hmdp.voucher.payment.service.IPaymentNotifyService;
import com.hmdp.voucher.payment.service.IPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 支付渠道异步通知的处理编排实现（原散落在 {@code PaymentNotifyController} 的解析 + 落地 + ack 决策逻辑统一收拢于此）。
 * 本类只产出中性 {@link NotifyAck}，至于应答成支付宝的 {@code success/failure} 字符串还是微信的 HTTP 200/4xx，
 * 是 Controller 作为 HTTP 适配器负责的协议翻译。只有 {@link NotifyAck#isAckSuccess()} 才应答成功 —— 金额不一致 /
 * 已关单等异常路径答成功会让渠道停止重推 → 静默丢单。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentNotifyServiceImpl implements IPaymentNotifyService {

    private final AlipayNotifyParser alipayNotifyParser;
    private final WechatNotifyParser wechatNotifyParser;
    private final IPaymentService paymentService;

    /** 处理支付宝异步通知：收 {@code @RequestParam Map} 的 form-urlencoded 报文 → 解析（验签/字段）→ applyNotify → 中性应答决策。
     * 验签/字段不过一律 {@link NotifyAck#reject}，绝不触碰任何状态、绝不 ack。 */
    @Override
    public NotifyAck handleAlipayNotify(Map<String, String> params) {
        AlipayNotifyParser.ParseResult parse = alipayNotifyParser.parse(params);
        if (!parse.accepted()) {
            log.warn("支付宝回调被拒绝：reason={}, outTradeNo={}",
                    parse.rejectReason(), params.get("out_trade_no"));
            return NotifyAck.reject(parse.rejectReason());
        }
        NotifyOutcome outcome = paymentService.applyNotify(parse.command());
        log.info("支付宝回调处理完成：outcome={}, outTradeNo={}",
                outcome, parse.command().getOutTradeNo());
        return NotifyAck.fromOutcome(outcome);
    }

    /** 处理微信支付（APIv3）异步通知：收原始报文 + 头（签名对 timestamp+nonce+原始 body 做，故用原始 String）→ 解析 → applyNotify → 中性应答决策。 */
    @Override
    public NotifyAck handleWechatNotify(String serial, String nonce, String timestamp, String signature, String body) {
        WechatNotifyParser.ParseResult parse = wechatNotifyParser.parse(serial, nonce, timestamp, signature, body);
        if (!parse.accepted()) {
            log.warn("微信回调被拒绝：reason={}", parse.rejectReason());
            return NotifyAck.reject(parse.rejectReason());
        }
        NotifyOutcome outcome = paymentService.applyNotify(parse.command());
        log.info("微信回调处理完成：outcome={}, outTradeNo={}",
                outcome, parse.command().getOutTradeNo());
        return NotifyAck.fromOutcome(outcome);
    }
}

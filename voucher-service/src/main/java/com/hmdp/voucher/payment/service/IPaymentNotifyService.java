package com.hmdp.voucher.payment.service;

import com.hmdp.voucher.payment.domain.NotifyAck;

import java.util.Map;

/** 支付渠道异步通知的处理编排接口：解析 + 落地 + ack 决策。实现见
 * {@link com.hmdp.voucher.payment.service.impl.PaymentNotifyServiceImpl}。
 * 之所以抽接口，是为了与本仓库 service 层「{@code IXxxService} 接口 + {@code XxxServiceImpl} 实现」的约定保持一致：
 * 接口只声明「做什么」，逻辑收敛在 Impl。 */
public interface IPaymentNotifyService {

    /** 处理支付宝异步通知：报文 → 解析（验签/字段）→ applyNotify → 中性应答决策。
     * 验签/字段不过一律 {@link com.hmdp.voucher.payment.domain.NotifyAck#reject}，绝不触碰任何状态、绝不 ack。 */
    NotifyAck handleAlipayNotify(Map<String, String> params);

    /** 处理微信支付（APIv3）异步通知：收原始报文 + 头（签名对 timestamp+nonce+原始 body 做）→ 解析 → applyNotify → 中性应答决策。 */
    NotifyAck handleWechatNotify(String serial, String nonce, String timestamp, String signature, String body);
}

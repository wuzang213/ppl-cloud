package com.hmdp.voucher.payment.dto;

import com.hmdp.voucher.payment.domain.PayProvider;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 「渠道支付结果」的内部命令对象 —— 回调 Controller 与主动查单任务都产出它，再由
 * {@link com.hmdp.voucher.payment.service.IPaymentService#applyNotify} 统一消费。抽出它是因为支付宝/微信的报文格式、验签、
 * 加密完全不同，但「验签之后该做什么」是同一套规则（金额核对、订单可支付性、条件更新、发消息）；差异留在各自解析器、共性收敛到这里，才不会出现「支付宝做了金额校验、微信忘了」，单测也能绕开报文直接构造命令。 */
@Data
@Accessors(chain = true)
public class PayNotifyCommand {

    /** 渠道服务商：决定落库的 {@code tb_voucher_order.pay_type}（2 支付宝 / 3 微信） */
    private PayProvider provider;

    /** 商户单号（反查支付单的唯一凭据） */
    private String outTradeNo;

    /** 渠道交易号（支付宝 trade_no / 微信 transaction_id） */
    private String tradeNo;

    /** 渠道侧金额，单位：分。<b>必须与支付单应付一致</b>，否则拒绝推进 */
    private Long amount;

    /** 渠道是否表示「已支付成功」 */
    private boolean success;

    /** 渠道原始状态串（支付宝 TRADE_SUCCESS / 微信 SUCCESS），仅用于日志 */
    private String rawStatus;

    /** 回调原文（已截断），落 {@code tb_payment.notify_payload} 供对账与排障 */
    private String payload;

    /** 结果到达时间，由应用写入（{@code LocalDateTime.now()}）。落 {@code notify_time}；成功时同时作为
     * {@code pay_time} —— 与 {@code tb_voucher_order.pay_time} 同一口径（都是应用墙钟，不是 DB 的 UTC 时钟）。 */
    private LocalDateTime notifyTime;
}

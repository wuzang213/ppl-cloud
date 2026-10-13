package com.hmdp.voucher.payment.dto;

import com.hmdp.voucher.domain.Payment;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 支付单的对外视图。刻意不直接把 {@code Payment} 实体返回给前端：实体里有 {@code notifyPayload} / {@code notifyTime}
 * 这类渠道回调原文（内部对账资料，含渠道签名），不该出现在面向用户的响应里；用显式视图挑出「能对外」的字段，以后给
 * 实体加字段也不会自动泄漏。金额一律是分，与 {@code tb_payment} 同口径，不做任何换算。 */
@Getter
@Setter
public class PaymentVO {

    private Long id;

    /** 渠道 code，取值见 {@code PayChannelType} */
    private String channel;

    /** 应付金额（分）= 券售价 */
    private Long amount;

    /** 券面值快照（分）—— 只用于展示，不是优惠 */
    private Long faceValue;

    /** 0 待支付 / 1 已支付 / 2 失败（本期不写入）/ 3 已关闭 */
    private Integer status;

    /** 商户单号（本系统生成，回调用它反查） */
    private String outTradeNo;

    /** 渠道交易号（渠道生成，支付成功后才有） */
    private String tradeNo;

    private LocalDateTime payTime;

    /** 渠道支付串（跳转 URL / 二维码串），供前端重新渲染支付入口 */
    private String prepayPayload;

    /** 无支付单时返回 {@code null}（订单从未发起过支付，属正常情况） */
    public static PaymentVO of(Payment payment) {
        if (payment == null) {
            return null;
        }
        PaymentVO vo = new PaymentVO();
        vo.setId(payment.getId());
        vo.setChannel(payment.getChannel());
        vo.setAmount(payment.getAmount());
        vo.setFaceValue(payment.getFaceValue());
        vo.setStatus(payment.getStatus());
        vo.setOutTradeNo(payment.getOutTradeNo());
        vo.setTradeNo(payment.getTradeNo());
        vo.setPayTime(payment.getPayTime());
        vo.setPrepayPayload(payment.getPrepayPayload());
        return vo;
    }
}

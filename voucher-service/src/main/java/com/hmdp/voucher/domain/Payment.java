package com.hmdp.voucher.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 支付单：一个秒杀券订单最多一条（DB 侧 {@code uk_order_id} 保证）；DDL 唯一权威是 sql/hmdp_voucher_payment.sql。
 * 金额一律<b>分</b>（bigint）：{@code amount}=应付（券售价），{@code faceValue}=面值快照（仅展示，不参与计算）。
 * {@code createTime}/{@code updateTime} 走 DB 默认值（本实例 NOW() 是 UTC，插入时保持 null）；{@code payTime}/{@code notifyTime} 由应用写。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_payment")
public class Payment implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键（由 RedisIdWorker.nextId("pay") 生成，非自增）
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 秒杀券订单 id（tb_voucher_order.id）
     */
    private Long orderId;

    /**
     * 下单用户 id（冗余，用于归属校验）
     */
    private Long userId;

    /** 支付渠道 code：alipay_page / wechat_native / wechat_h5 / mock
     * @see com.hmdp.voucher.payment.domain.PayChannelType */
    private String channel;

    /**
     * 应付金额，单位：分。= tb_voucher.pay_value（券的售价 = 本次交易真正收的钱）
     */
    private Long amount;

    /** 券面值快照，单位：分。= tb_voucher.actual_value。
     * 仅用于展示，不参与任何支付计算：与 {@code amount} 的差额不代表优惠，只体现「面值 ≠ 售价」的售卖关系。 */
    private Long faceValue;

    /** 状态：0 PENDING / 1 SUCCESS / 2 FAILED / 3 CLOSED
     * @see com.hmdp.voucher.payment.domain.PaymentStatus */
    private Integer status;

    /**
     * 商户单号（回调按它反查支付单）
     */
    private String outTradeNo;

    /**
     * 渠道交易号：支付宝 trade_no / 微信 transaction_id
     */
    private String tradeNo;

    /**
     * 预下单返回（payUrl / code_url / h5_url），排障用
     */
    private String prepayPayload;

    /**
     * 回调原文（截断），排障与对账用
     */
    private String notifyPayload;

    /**
     * 回调到达时间（应用写）
     */
    private LocalDateTime notifyTime;

    /**
     * 支付成功时间（应用写）
     */
    private LocalDateTime payTime;

    /**
     * 创建时间（DB 默认值）
     */
    private LocalDateTime createTime;

    /**
     * 更新时间（DB 默认值 / ON UPDATE）
     */
    private LocalDateTime updateTime;
}

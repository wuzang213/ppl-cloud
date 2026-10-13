package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.voucher.domain.Voucher;

/** 服务端计价 —— 支付金额的唯一来源。入口签名只接受 {@link Voucher} 实体，结构上不给前端金额留通道（否则任何人
 * 都能把 100 元的券改成 1 分钱下单）。领域模型：{@code pay_value}（分）= 券售价 = 本次应收的钱 → {@code amount}；
 * {@code actual_value}（分）= 券面值 → {@code faceValue}（快照，仅展示）。两者差额不是优惠，只体现「面值 ≠ 售价」的售卖关系；本仓库没有商品模块，不存在「商品金额被券抵扣」，第三方渠道只接受 {@code amount} 一个金额值。 */
public final class PaymentPricing {

    private PaymentPricing() {
    }

    /** 从券实体算出应付与面值。
     * @param voucher 券实体（由 voucherId 反查得到），为 null 表示券不存在
     * @throws BadRequestException 券不存在（→ 400）@throws BizIllegalException 金额字段缺失或为负（券数据坏了，不是用户的错） */
    public static Pricing price(Voucher voucher) {
        if (voucher == null) {
            throw new BadRequestException(VoucherConstants.VOUCHER_NOT_FOUND);
        }
        Long payValue = voucher.getPayValue();
        Long actualValue = voucher.getActualValue();
        if (payValue == null || actualValue == null || payValue < 0 || actualValue < 0) {
            throw new BizIllegalException(VoucherConstants.VOUCHER_AMOUNT_INVALID
                    + "（voucherId=" + voucher.getId() + ", payValue=" + payValue
                    + ", actualValue=" + actualValue + "）");
        }
        return new Pricing(payValue, actualValue);
    }

    /** 计价结果。字段含义见类注释，单位一律是分。
     * @param amount 应付金额（分）—— 落 {@code tb_payment.amount}，也是唯一传给第三方渠道的金额
     * @param faceValue 券面值快照（分）—— 落 {@code tb_payment.face_value}，仅展示，不参与任何计算 */
    public record Pricing(long amount, long faceValue) {
    }
}

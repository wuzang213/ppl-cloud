package com.hmdp.voucher.payment;

import java.math.BigDecimal;

/** 支付宝金额换算：分 → 元的两位小数字符串。支付宝 {@code total_amount} 要「元」，本仓库金额都是「分」（bigint），
 * 换算搞反（把分当元传）不会报错 —— 1 元的券显示成 100 元、静默多收 100 倍，是资损防线，须由零依赖单测钉死。
 * 实现全程用 {@link BigDecimal}（{@code BigDecimal.valueOf(unscaledVal, scale)} 是按位缩放、非除法），禁止浮点；{@code toPlainString()} 保证输出 {@code "10.00"} 而非 {@code "1E+1"}。微信侧不需要本类（{@code amount.total} 直接收分）。 */
public final class AlipayAmount {

    private AlipayAmount() {
    }

    /** 分 → 元（两位小数字符串）。示例：1000 → "10.00"，1 → "0.01"，0 → "0.00"，123456789 → "1234567.89"。
     * @param fen 金额（分），不允许为负
     * @throws IllegalArgumentException fen 为负（负金额只会来自上游算错，尽早炸掉比静默传给渠道好） */
    public static String fenToYuanString(long fen) {
        if (fen < 0) {
            throw new IllegalArgumentException("支付宝金额不能为负: fen=" + fen);
        }
        return BigDecimal.valueOf(fen, 2).toPlainString();
    }

    /** 元字符串 → 分（{@link #fenToYuanString} 的反向），用于把渠道回传金额拉回统一口径（分）再与支付单比对。
     * 解析失败必须返回 {@code null}，不许返回 0 或猜一个值 —— 调用方据此按「金额无法确认」处理（拒绝推进 + 告警）。
     * @return 分；无法精确解析时返回 {@code null} */
    public static Long yuanToFen(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return null;
        }
        try {
            // movePointRight 是十进制移位，不经过浮点；longValueExact 保证不会静默截断小数
            return new BigDecimal(yuan.trim()).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}

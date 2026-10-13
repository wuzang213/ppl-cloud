package com.hmdp.voucher.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AlipayAmount} 的纯逻辑单测。零外部依赖。
 *
 * <p>这组断言是<b>资损防线</b>：换算写反（分当元传）不会报错，只会静默多收 100 倍。
 * 因此断言必须精确到字符串（含小数点与末尾的 0），用 {@link BigDecimal} 比较数值，
 * <b>不能</b>用 {@code Double.parseDouble} 之类的浮点路径。
 */
class AlipayAmountTest {

    @Test
    @DisplayName("分 → 元：保留两位小数，且不丢精度")
    void fenToYuan_keepsTwoDecimals() {
        assertEquals("10.00", AlipayAmount.fenToYuanString(1000));
        assertEquals("1.00", AlipayAmount.fenToYuanString(100));
        assertEquals("0.01", AlipayAmount.fenToYuanString(1));
        assertEquals("9.99", AlipayAmount.fenToYuanString(999));
        assertEquals("0.00", AlipayAmount.fenToYuanString(0));
        assertEquals("0.10", AlipayAmount.fenToYuanString(10));
    }

    @Test
    @DisplayName("大额：不出现科学计数法，也不丢位")
    void fenToYuan_largeAmount() {
        String yuan = AlipayAmount.fenToYuanString(123456789L);
        assertEquals("1234567.89", yuan);
        // 显式排除科学计数法（toPlainString 的语义保证）
        assertTrue(yuan.indexOf('E') < 0 && yuan.indexOf('e') < 0, "不得出现科学计数法: " + yuan);
    }

    @Test
    @DisplayName("数值与 BigDecimal 完全一致（不走浮点）")
    void fenToYuan_matchesBigDecimalExactly() {
        for (long fen : new long[]{0L, 1L, 7L, 999L, 1000L, 100000L, 123456789L, Long.MAX_VALUE / 1000}) {
            BigDecimal expected = BigDecimal.valueOf(fen, 2);
            assertEquals(0, new BigDecimal(AlipayAmount.fenToYuanString(fen)).compareTo(expected),
                    "fen=" + fen + " 换算不一致");
        }
    }

    @Test
    @DisplayName("负金额直接拒绝：只可能来自上游算错，早炸胜过静默传给渠道")
    void fenToYuan_rejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> AlipayAmount.fenToYuanString(-1));
        assertThrows(IllegalArgumentException.class, () -> AlipayAmount.fenToYuanString(-1000));
    }
}

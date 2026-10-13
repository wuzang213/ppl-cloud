package com.hmdp.voucher.payment.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「发起支付」请求体的<b>结构性</b>防线。零外部依赖（纯反射）。
 *
 * <p>本测试不复测任何业务逻辑，只做一件事：确保请求体里<b>不会长出金额字段</b>。
 *
 * <p>为什么值得单独写一个测试：金额一旦能从请求体传进来，就等于给
 * 「1 分钱买 100 元券」开了口子 —— 而这类改动往往看起来很无辜
 * （「前端想显示一下金额，顺手加个字段接收」），code review 也容易放过。
 * 让它在 CI/门禁层面立刻变红，比靠人记得更可靠。
 */
class PayOrderRequestTest {

    /** 只要字段名里出现这些词根，就判定为「金额字段」 */
    private static final List<String> FORBIDDEN_TOKENS =
            Arrays.asList("amount", "payvalue", "actualvalue", "price", "money", "total", "fee", "cost");

    @Test
    @DisplayName("请求体只有 channel 一个字段（新增字段必须显式改这个测试）")
    void onlyChannelField() {
        List<String> fields = Arrays.stream(PayOrderRequest.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());
        assertEquals(List.of("channel"), fields,
                "PayOrderRequest 的字段集合变了。若是新增业务字段请同步本测试；"
                        + "若是金额字段，请立刻停止 —— 金额只能由服务端从 tb_voucher 计算");
    }

    @Test
    @DisplayName("任何字段名都不得命中金额词根")
    void noAmountLikeField() {
        for (Field f : PayOrderRequest.class.getDeclaredFields()) {
            String lower = f.getName().toLowerCase(Locale.ROOT);
            for (String token : FORBIDDEN_TOKENS) {
                assertTrue(!lower.contains(token),
                        "PayOrderRequest 出现了疑似金额字段 '" + f.getName()
                                + "'（命中词根 '" + token + "'）—— 前端传金额是资损漏洞，禁止");
            }
        }
    }
}

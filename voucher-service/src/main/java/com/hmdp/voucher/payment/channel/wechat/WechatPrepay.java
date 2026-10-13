package com.hmdp.voucher.payment.channel.wechat;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** 微信预下单的两个纯函数：金额换算与超时时刻格式化。单独抽出是因为这两处写错都不报错、只静默出错 —— 金额少乘或多乘
 * 100 倍是资损，超时格式不合规范是「调渠道必被拒」但错误信息指向别处；它们必须能被零外部依赖的单测钉死。
 * 与支付宝的差异：微信 {@code amount.total} 直接收「分」、不做换算，这里只做「能不能装进 {@code Integer}」的边界校验。 */
public final class WechatPrepay {

    /** 微信要求的 RFC3339 形态：{@code yyyy-MM-ddTHH:mm:ss±HH:mm}。刻意不带毫秒 ——
     * 渠道对 {@code time_expire} 的格式比 RFC3339 本身更严。 */
    private static final DateTimeFormatter TIME_EXPIRE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private WechatPrepay() {
    }

    /** 分 → 微信 {@code amount.total}。微信该字段是 32 位整数，上限 {@link Integer#MAX_VALUE} 分 ≈ 2147 万元；本仓库金额来自
     * {@code bigint}，被写坏成超大值时强转会静默截断成另一个金额并作为「合法金额」发给渠道（会真按错金额扣款），故宁可抛异常。
     * @throws IllegalArgumentException fen 为负或超出 int 范围 */
    public static int toTotal(long fen) {
        if (fen < 0) {
            throw new IllegalArgumentException("微信支付金额不能为负: fen=" + fen);
        }
        if (fen > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("微信支付金额超出其 32 位上限（分）: fen=" + fen);
        }
        return (int) fen;
    }

    /** 支付超时时刻（绝对时间，带时区偏移）。必须基于绝对时刻而非 DB 的 {@code NOW()}：本实例 {@code NOW()} 是 UTC，
     * 直接拿它拼字符串会少 8 小时，表现为「用户还没付就已经过期」；这里用系统墙钟 + 本机时区偏移，渠道侧按绝对时刻理解。 */
    public static String timeExpire(int ttlMinutes) {
        return ZonedDateTime.now().plusMinutes(ttlMinutes).format(TIME_EXPIRE_FORMAT);
    }
}

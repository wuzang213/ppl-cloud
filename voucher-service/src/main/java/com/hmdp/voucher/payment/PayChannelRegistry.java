package com.hmdp.voucher.payment;

import com.hmdp.common.exception.BadRequestException;
import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.voucher.payment.domain.PayChannelType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 渠道注册表：把所有 {@link PayChannel} Bean 按 code 索引。装配期做三重 fail fast（启动即暴露，不留到运行期）：
 * ①code 不能为空；②code 必须登记在 {@link PayChannelType}（防加了枚举没加实现或反之）；
 * ③code 全局唯一（防两个实现抢同一渠道、静默覆盖）。 */
@Component
public class PayChannelRegistry {

    private final Map<String, PayChannel> byCode;

    public PayChannelRegistry(List<PayChannel> channels) {
        Map<String, PayChannel> map = new LinkedHashMap<>();
        for (PayChannel channel : channels) {
            String code = channel.code();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException("支付渠道 " + channel.getClass().getName() + " 的 code 为空");
            }
            String key = code.trim().toLowerCase();
            if (PayChannelType.fromCode(key) == null) {
                throw new IllegalStateException("支付渠道 code 未登记到 PayChannelType: " + code);
            }
            if (map.put(key, channel) != null) {
                throw new IllegalStateException("支付渠道 code 重复，请检查实现类: " + code);
            }
        }
        this.byCode = Collections.unmodifiableMap(map);
    }

    /** 按 code 取渠道。非法 code 抛 {@link BadRequestException}（→ HTTP 400），并在消息里回显可选渠道。 */
    public PayChannel get(String code) {
        String key = code == null ? "" : code.trim().toLowerCase();
        PayChannel channel = byCode.get(key);
        if (channel == null) {
            throw new BadRequestException(VoucherConstants.PAY_CHANNEL_NOT_SUPPORTED
                    + "，当前可用：" + codes());
        }
        return channel;
    }

    public PayChannel get(PayChannelType type) {
        return get(type.getCode());
    }

    /** 按 code 取渠道，取不到返回 {@code null}（不抛异常）。供后台任务拿库里的 {@code channel} 值回查实现：库里
     * 有历史值/脏值时记错误日志并跳过，而不是每轮抛面向用户的 400（否则告警日志里全是同一个脏值）。面向用户的入口
     * 一律用 {@link #get(String)}，让非法渠道得到 400。 */
    public PayChannel find(String code) {
        if (code == null) {
            return null;
        }
        return byCode.get(code.trim().toLowerCase());
    }

    /** 已装配的渠道 code（字典序，便于日志与错误消息稳定可比）。注意：只表示「实现存在」，不代表凭据已配
     * （后者要看 {@link PayChannel#isReady()}）。 */
    public Set<String> codes() {
        return Collections.unmodifiableSet(new TreeSet<>(byCode.keySet()));
    }
}

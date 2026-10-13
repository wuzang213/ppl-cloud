package com.hmdp.voucher.payment.support;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.mapper.PaymentMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存态假 {@link PaymentMapper}（零外部依赖，不连库）。
 *
 * <p>刻意模拟真实 DB 的三处行为，否则测试会给出虚假的信心：
 * <ol>
 *   <li><b>DB 默认时间戳</b>：{@code insert} 时把 {@code create_time/update_time} 填成「DB 当前时间」，
 *       否则过期判断永远拿不到时间基准；</li>
 *   <li><b>条件更新的影响行数</b>：{@code advanceStatus} / {@code markClosed} / {@code rotatePending}
 *       严格按 {@code WHERE ... AND status = 0} 返回 1 或 0 —— 幂等测试全靠这个返回值；</li>
 *   <li><b>update_time 随更新走</b>：每次成功的写操作都把 {@code update_time} 推到当前 DB 时间，
 *       因为「预下单是否过期」正是以它为基准。</li>
 * </ol>
 *
 * <p>另外把每类写操作的<b>成功次数</b>暴露出来（{@link #advanceCount} 等），
 * 让「重复回调只推进一次」可以被直接断言。
 */
public class InMemoryPaymentMapper extends BaseMapperStub<Payment> implements PaymentMapper {

    private final Map<Long, Payment> byId = new LinkedHashMap<>();

    /** 模拟的 DB 时钟（UTC），测试可自由推进 */
    private LocalDateTime dbNow = LocalDateTime.of(2026, 9, 26, 4, 0, 0);

    /** 各类写操作真正生效的次数 */
    public int insertCount;
    public int advanceCount;
    public int markClosedCount;
    public int markClosedByOrderCount;
    public int saveNotifyCount;
    public int savePrepayCount;
    public int rotateCount;

    public void setDbNow(LocalDateTime dbNow) {
        this.dbNow = dbNow;
    }

    /** 让模拟时钟前进指定分钟数（用于构造「预下单已过期」） */
    public void advanceDbClockMinutes(long minutes) {
        this.dbNow = this.dbNow.plusMinutes(minutes);
    }

    public LocalDateTime dbNow() {
        return dbNow;
    }

    public Payment get(Long id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    /** 直接塞一条支付单（模拟历史数据 / 绕过 prepay 构造前置状态） */
    public void put(Payment payment) {
        if (payment.getCreateTime() == null) {
            payment.setCreateTime(dbNow);
        }
        payment.setUpdateTime(dbNow);
        byId.put(payment.getId(), payment);
    }

    // ---------------- 被被测代码真正调用的方法 ----------------

    @Override
    public int insert(Payment entity) {
        // 模拟 DB 的 CURRENT_TIMESTAMP 默认值
        entity.setCreateTime(dbNow);
        entity.setUpdateTime(dbNow);
        byId.put(entity.getId(), entity);
        insertCount++;
        return 1;
    }

    @Override
    public Payment selectByOrderId(Long orderId) {
        return byId.values().stream()
                .filter(p -> orderId != null && orderId.equals(p.getOrderId()))
                .findFirst().orElse(null);
    }

    @Override
    public Payment selectByOutTradeNo(String outTradeNo) {
        return byId.values().stream()
                .filter(p -> outTradeNo != null && outTradeNo.equals(p.getOutTradeNo()))
                .findFirst().orElse(null);
    }

    @Override
    public List<Payment> selectStalePending(int ttlMinutes, int limit) {
        LocalDateTime cutoff = dbNow.minusMinutes(ttlMinutes);
        List<Payment> result = new ArrayList<>();
        byId.values().stream()
                .filter(p -> Integer.valueOf(0).equals(p.getStatus()))
                .filter(p -> p.getUpdateTime() != null && !p.getUpdateTime().isAfter(cutoff))
                .sorted(Comparator.comparing(Payment::getId))
                .limit(limit)
                .forEach(result::add);
        return result;
    }

    @Override
    public LocalDateTime selectDbNow() {
        return dbNow;
    }

    @Override
    public int advanceStatus(Long id, int toStatus, String tradeNo,
                             LocalDateTime payTime, LocalDateTime notifyTime, String payload) {
        Payment p = byId.get(id);
        if (p == null || !Integer.valueOf(0).equals(p.getStatus())) {
            return 0;
        }
        p.setStatus(toStatus);
        p.setTradeNo(tradeNo);
        p.setPayTime(payTime);
        p.setNotifyTime(notifyTime);
        p.setNotifyPayload(payload);
        p.setUpdateTime(dbNow);
        advanceCount++;
        return 1;
    }

    @Override
    public int markClosed(Long id) {
        Payment p = byId.get(id);
        if (p == null || !Integer.valueOf(0).equals(p.getStatus())) {
            return 0;
        }
        p.setStatus(3);
        p.setUpdateTime(dbNow);
        markClosedCount++;
        return 1;
    }

    @Override
    public int markClosedByOrderId(Long orderId) {
        Payment p = selectByOrderId(orderId);
        if (p == null || !Integer.valueOf(0).equals(p.getStatus())) {
            return 0;
        }
        p.setStatus(3);
        p.setUpdateTime(dbNow);
        markClosedByOrderCount++;
        return 1;
    }

    @Override
    public int saveNotifyPayload(Long id, LocalDateTime notifyTime, String payload) {
        Payment p = byId.get(id);
        if (p == null) {
            return 0;
        }
        p.setNotifyTime(notifyTime);
        p.setNotifyPayload(payload);
        p.setUpdateTime(dbNow);
        saveNotifyCount++;
        return 1;
    }

    @Override
    public int savePrepayPayload(Long id, String payload) {
        Payment p = byId.get(id);
        if (p == null) {
            return 0;
        }
        p.setPrepayPayload(payload);
        p.setUpdateTime(dbNow);
        savePrepayCount++;
        return 1;
    }

    @Override
    public int rotatePending(Long id, String channel, long amount, long faceValue, String outTradeNo) {
        Payment p = byId.get(id);
        if (p == null || !Integer.valueOf(0).equals(p.getStatus())) {
            return 0;
        }
        p.setChannel(channel);
        p.setAmount(amount);
        p.setFaceValue(faceValue);
        p.setOutTradeNo(outTradeNo);
        p.setTradeNo(null);
        p.setPrepayPayload(null);
        p.setNotifyPayload(null);
        p.setNotifyTime(null);
        p.setPayTime(null);
        p.setUpdateTime(dbNow);
        rotateCount++;
        return 1;
    }
}

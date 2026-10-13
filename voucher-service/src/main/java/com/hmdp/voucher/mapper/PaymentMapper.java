package com.hmdp.voucher.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.voucher.domain.Payment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 支付单 Mapper。用注解 SQL 而非 {@code QueryWrapper}：①幂等靠 DB 条件更新（{@code UPDATE ... WHERE id=? AND
 * status=0}，用影响行数判断自己是否第一个，条件更新只能写进 SQL）；②不依赖 {@code mapper-locations}，绕开
 * 「加了 XML 但没配路径导致 SQL 静默不加载」。⚠️ 时间基准：本实例 {@code time_zone=SYSTEM}=UTC，{@code NOW()} 比本地墙钟少 8 小时 —— 凡与表内 {@code create_time}/{@code update_time} 比较一律在 SQL 里用 {@code NOW()}/{@code DATE_SUB(NOW(),…)}，用应用侧 {@code LocalDateTime.now()} 会精确差 8 小时（表现为过期判断永不成立或立刻全部过期）；需把 DB 时钟取回 Java 比较用 {@link #selectDbNow()}。 */
public interface PaymentMapper extends BaseMapper<Payment> {

    /** 按订单查支付单（{@code uk_order_id} 保证最多一条） */
    @Select("SELECT * FROM tb_payment WHERE order_id = #{orderId}")
    Payment selectByOrderId(@Param("orderId") Long orderId);

    /** 回调按商户单号反查支付单 */
    @Select("SELECT * FROM tb_payment WHERE out_trade_no = #{outTradeNo}")
    Payment selectByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /** 扫出「停留在 PENDING 且超过 ttl 分钟没有活动」的支付单，供 PayQueryTask 主动查单。判据用 {@code update_time}
     * 而非 {@code create_time}：预下单过期后会原地轮换出新 {@code out_trade_no}，用 create_time 会「刚轮换完就被判过期」。 */
    @Select("SELECT * FROM tb_payment WHERE status = 0 "
            + "AND update_time <= DATE_SUB(NOW(), INTERVAL #{ttlMinutes} MINUTE) "
            + "ORDER BY id LIMIT #{limit}")
    List<Payment> selectStalePending(@Param("ttlMinutes") int ttlMinutes, @Param("limit") int limit);

    /** 取 DB 当前时间（UTC）。用于把 DB 时钟带回 Java 与表内时间比较，避免 8 小时偏差。 */
    @Select("SELECT NOW()")
    LocalDateTime selectDbNow();

    /** PENDING → SUCCESS / FAILED（条件更新）。返回值是关键判据：{@code 1} = 本次是第一个推进的，需继续更新订单 +
     * 写 outbox；{@code 0} = 支付单已不在 PENDING（重复回调 / 并发 / 已关单），调用方必须停止后续副作用（否则
     * 重复积分、重复投递）。@param toStatus {@code PaymentStatus.SUCCESS.getValue()} 或 {@code FAILED.getValue()} */
    @Update("UPDATE tb_payment SET status = #{toStatus}, trade_no = #{tradeNo}, "
            + "pay_time = #{payTime}, notify_time = #{notifyTime}, notify_payload = #{payload} "
            + "WHERE id = #{id} AND status = 0")
    int advanceStatus(@Param("id") Long id,
                      @Param("toStatus") int toStatus,
                      @Param("tradeNo") String tradeNo,
                      @Param("payTime") LocalDateTime payTime,
                      @Param("notifyTime") LocalDateTime notifyTime,
                      @Param("payload") String payload);

    /** PENDING → CLOSED（订单超时关单时调用）。只改 status，不动 {@code notify_*} 与 {@code trade_no}：关单前可能
     * 收到过被拒绝的回调，那些原文是资损证据，不能被抹掉。{@code WHERE status = 0} 保证永不覆盖已成功的支付单。 */
    @Update("UPDATE tb_payment SET status = 3 WHERE id = #{id} AND status = 0")
    int markClosed(@Param("id") Long id);

    /** 按订单关掉它的 PENDING 支付单（订单超时关单时调用）。用一条 SQL 而非「先 {@link #selectByOrderId} 读出、
     * 判断 PENDING、再按 id 关」：后者读写之间隔着网络往返，用户可能已付款成功，会拿过期快照把已付款的支付单
     * 关掉 → 用户付了钱而订单是取消态。{@code WHERE status = 0} 同时保证已成功的支付单永不被关单动作覆盖。 */
    @Update("UPDATE tb_payment SET status = 3 WHERE order_id = #{orderId} AND status = 0")
    int markClosedByOrderId(@Param("orderId") Long orderId);

    /** 留存回调原文（含被拒绝的回调）：拒绝推进时不留档，「用户付了钱、订单却没变已支付」在库里就毫无痕迹。 */
    @Update("UPDATE tb_payment SET notify_time = #{notifyTime}, notify_payload = #{payload} WHERE id = #{id}")
    int saveNotifyPayload(@Param("id") Long id,
                          @Param("notifyTime") LocalDateTime notifyTime,
                          @Param("payload") String payload);

    @Update("UPDATE tb_payment SET prepay_payload = #{payload} WHERE id = #{id}")
    int savePrepayPayload(@Param("id") Long id, @Param("payload") String payload);

    /** 原地轮换：预下单已过期而未支付时换新 {@code out_trade_no} 重发。必须原地而非插新行（{@code uk_order_id}
     * 保证一单一支付单，插新行必撞唯一键，拒绝重试则用户在 prepay-ttl(5min)~订单超时(15min) 间无法支付）；
     * 同时清空旧 {@code trade_no}/{@code notify_*} 且调用方先打日志，SQL 用 {@code NOW()} 重置 update_time（否则轮换完即被判过期）。 */
    @Update("UPDATE tb_payment SET channel = #{channel}, amount = #{amount}, face_value = #{faceValue}, "
            + "out_trade_no = #{outTradeNo}, trade_no = NULL, prepay_payload = NULL, notify_payload = NULL, "
            + "notify_time = NULL, pay_time = NULL, status = 0, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0")
    int rotatePending(@Param("id") Long id,
                      @Param("channel") String channel,
                      @Param("amount") long amount,
                      @Param("faceValue") long faceValue,
                      @Param("outTradeNo") String outTradeNo);
}

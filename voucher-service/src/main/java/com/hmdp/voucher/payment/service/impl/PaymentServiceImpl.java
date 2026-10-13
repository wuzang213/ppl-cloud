package com.hmdp.voucher.payment.service.impl;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.OrderPaidMessage;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.common.exception.ForbiddenException;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.RedisIdWorker;
import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.mapper.PaymentMapper;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.voucher.mapper.VoucherOrderMapper;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.PayChannelRegistry;
import com.hmdp.voucher.payment.PaymentPricing;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.NotifyOutcome;
import com.hmdp.voucher.payment.domain.PayChannelType;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.domain.PrepayResult;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;
import com.hmdp.voucher.payment.service.IPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/** 支付单领域服务实现：预下单、支付结果落地、幂等与资损防线。三条原则：①金额只从服务端算（唯一入口
 * {@link PaymentPricing}，渠道只接受 {@code amount}，{@code faceValue} 是快照不参与计算）；②幂等靠 DB 条件更新
 * （{@code UPDATE ... WHERE status=0} 的影响行数是判据，先读再写会并发双写）；③时间基准不可互串（与表内 time 比较用 {@link PaymentMapper#selectDbNow()}，写 pay_time/notify_time 用应用墙钟 LocalDateTime.now()，搞混差 8 小时）。{@link PaymentStatus#FAILED} 保留但本期不写入：置 FAILED 而订单仍未支付，会让用户在订单超时前无法重新发起支付。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentServiceImpl implements IPaymentService {

    /** 回调原文落库前的截断长度：排障与对账够用，避免一条报文把行撑爆 */
    private static final int PAYLOAD_MAX_LENGTH = 4000;

    private final PaymentMapper paymentMapper;
    private final VoucherOrderMapper voucherOrderMapper;
    private final VoucherMapper voucherMapper;
    private final PayChannelRegistry channelRegistry;
    private final PayProperties payProperties;
    private final RedisIdWorker redisIdWorker;
    private final OutboxWriter outboxWriter;

    // ==================================================================
    // 预下单
    // ==================================================================

    /** 发起支付（不带客户端 IP）。两个重载都标 {@code @Transactional}：同类内部调用不走代理，若只给四参版标注，
     * 从本方法进来时自调用不会开启事务；两个都标后无论从哪个入口进来事务都成立（内层自调用复用外层事务）。
     * @param orderId 秒杀券订单 id @param channelCode 渠道 code，留空用 {@code hmdp.pay.default-channel} @param userId 当前登录用户（Controller 从登录态取出后传入，便于单测脱离 ThreadLocal） */
    @Transactional
    @Override
    public PrepayResult prepay(Long orderId, String channelCode, Long userId) {
        return prepay(orderId, channelCode, userId, null);
    }

    /** 发起支付：返回「怎么付」的信息。同一订单（{@code uk_order_id} 保证一单一支付单）：无支付单 → 新建；
     * 有 PENDING 未过期 → 复用（连支付串一起复用，不再打扰渠道）；有 PENDING 已过期 → 原地轮换出新商户单号；
     * 换渠道 → 拒绝（一单一支付单）。@param clientIp 用户端 IP，可空；微信 H5 必填（{@code scene_info.payer_client_ip}），由 Controller 取真实来源 IP 传入，本层不猜 */
    @Transactional
    @Override
    public PrepayResult prepay(Long orderId, String channelCode, Long userId, String clientIp) {
        VoucherOrder order = requireOwnedUnpaidOrder(orderId, userId);
        PayChannel channel = resolveChannel(channelCode);

        Payment payment = paymentMapper.selectByOrderId(orderId);
        if (payment == null) {
            payment = createPendingPayment(order, channel);
        } else {
            ReuseDecision decision = decideReuse(payment, channel);
            if (decision == ReuseDecision.REUSE_PAYLOAD) {
                // 支付串已存在且未过期：直接回放，不再打扰渠道。
                // 这同时回避了「同一商户单号重复预下单」在部分渠道会报错的不确定性。
                log.info("复用未过期的支付单：orderId={}, paymentId={}, outTradeNo={}",
                        orderId, payment.getId(), payment.getOutTradeNo());
                return PrepayResult.forChannel(payment.getChannel(), payment.getPrepayPayload());
            }
            if (decision == ReuseDecision.ROTATE) {
                payment = rotate(payment, order, channel);
            }
            // CALL_CHANNEL：沿用这一行与这个商户单号，只是重新问一次渠道
        }

        PrepayResult result = channel.prepay(payment, clientIp);
        if (result == null || result.isEmpty()) {
            throw new BizIllegalException(VoucherConstants.PREPAY_FAILED + "（channel=" + channel.code() + "）");
        }
        paymentMapper.savePrepayPayload(payment.getId(), truncate(result.getPayload()));
        return result;
    }

    private enum ReuseDecision {
        /** 复用已存支付单与已存支付串 */
        REUSE_PAYLOAD,
        /** 复用支付单这一行与商户单号，但支付串还没生成（上次在渠道调用处失败），需要重新问渠道 */
        CALL_CHANNEL,
        /** 复用支付单这一行，但换一个全新商户单号（预下单已过期） */
        ROTATE
    }

    /** 判断已有支付单怎么复用。非 PENDING 的状态直接驳回 —— 那是「已付过」或「已终止」，都不能再发起支付。 */
    private ReuseDecision decideReuse(Payment payment, PayChannel channel) {
        if (!channel.code().equalsIgnoreCase(payment.getChannel())) {
            throw new BadRequestException(VoucherConstants.PAYMENT_CHANNEL_LOCKED
                    + "（已有渠道 " + payment.getChannel() + "）");
        }
        PaymentStatus status = PaymentStatus.of(payment.getStatus());
        if (status == PaymentStatus.SUCCESS) {
            throw new BadRequestException(VoucherConstants.PAYMENT_ALREADY_SUCCESS);
        }
        if (status != PaymentStatus.PENDING) {
            throw new BadRequestException(VoucherConstants.PAYMENT_TERMINAL + "（当前状态 " + status + "）");
        }
        if (isExpired(payment)) {
            return ReuseDecision.ROTATE;
        }
        if (payment.getPrepayPayload() == null || payment.getPrepayPayload().isBlank()) {
            return ReuseDecision.CALL_CHANNEL;
        }
        return ReuseDecision.REUSE_PAYLOAD;
    }

    /** 预下单是否已过期。判据是「最后一次活动（{@code update_time}）+ {@code prepay-ttl-minutes}」早于 DB 当前时间，
     * 两个时间都取自 DB（{@link PaymentMapper#selectDbNow()}），不能用应用墙钟 —— 本实例 {@code NOW()} 是 UTC，差 8
     * 小时会让过期判断永远不成立（或立刻成立）。取不到 DB 时钟时按未过期处理：误轮换会让用户手里的支付串失效。 */
    private boolean isExpired(Payment payment) {
        LocalDateTime lastActivity = payment.getUpdateTime() != null
                ? payment.getUpdateTime() : payment.getCreateTime();
        LocalDateTime dbNow = paymentMapper.selectDbNow();
        if (lastActivity == null || dbNow == null) {
            log.warn("支付单缺少时间字段或取不到 DB 时钟，按未过期处理：paymentId={}", payment.getId());
            return false;
        }
        return !dbNow.isBefore(lastActivity.plusMinutes(payProperties.getPrepayTtlMinutes()));
    }

    /** 原地轮换：换新商户单号，清掉上一笔交易的痕迹。轮换前把旧商户单号打进 WARN 日志 —— 「旧交易的支付结果在
     * 轮换后到达」时，{@link #applyNotify} 会因反查不到支付单而报资损告警，那时这条日志是唯一线索。 */
    private Payment rotate(Payment payment, VoucherOrder order, PayChannel channel) {
        PaymentPricing.Pricing pricing = PaymentPricing.price(voucherMapper.selectById(order.getVoucherId()));
        String newOutTradeNo = newOutTradeNo();
        log.warn("预下单已过期，原地轮换商户单号：paymentId={}, orderId={}, oldOutTradeNo={}, newOutTradeNo={}",
                payment.getId(), payment.getOrderId(), payment.getOutTradeNo(), newOutTradeNo);
        int updated = paymentMapper.rotatePending(payment.getId(), channel.code(),
                pricing.amount(), pricing.faceValue(), newOutTradeNo);
        if (updated == 0) {
            // 并发下另一个请求刚推进/关闭了它 —— 不能假装成功，让调用方重试
            throw new BizIllegalException(VoucherConstants.PREPAY_FAILED + "（支付单状态已变化，请重试）");
        }
        return payment.setChannel(channel.code())
                .setAmount(pricing.amount())
                .setFaceValue(pricing.faceValue())
                .setOutTradeNo(newOutTradeNo)
                .setStatus(PaymentStatus.PENDING.getValue())
                .setTradeNo(null)
                .setPrepayPayload(null)
                .setNotifyPayload(null)
                .setNotifyTime(null)
                .setPayTime(null);
    }

    // ==================================================================
    // 支付结果落地（回调 / 主动查单共用）
    // ==================================================================

    /** 落地一次「渠道支付结果」（支付宝回调 / 微信回调 / 主动查单三条入口共用）。顺序刻意如此，每步都能独立失败
     * 并留痕：反查支付单（查不到是资损告警）→ 非成功通知只记录不推进 → 已是 SUCCESS 幂等应答 → 已是 CLOSED/FAILED
     * 却收到成功 / 金额不一致 / 订单已不可支付：均拒绝推进 + 留档 + 资损告警 → 条件更新支付单（影响行数 0 说明并发重放，幂等返回）→ 条件更新订单，只有推进成功才写 {@code ORDER_PAID} outbox。 */
    @Transactional
    @Override
    public NotifyOutcome applyNotify(PayNotifyCommand cmd) {
        if (cmd == null || cmd.getOutTradeNo() == null || cmd.getOutTradeNo().isBlank()) {
            log.warn("支付通知缺少商户单号，无法处理：rawStatus={}", cmd == null ? null : cmd.getRawStatus());
            return NotifyOutcome.PAYMENT_NOT_FOUND;
        }

        Payment payment = paymentMapper.selectByOutTradeNo(cmd.getOutTradeNo());
        if (payment == null) {
            log.error("【支付告警·需人工介入】收到未知商户单号的回调，无法关联支付单"
                            + "（可能用户已付款但系统无记录，或旧交易在轮换后回调）：provider={}, outTradeNo={}, "
                            + "tradeNo={}, amount={}分, payload={}",
                    cmd.getProvider(), cmd.getOutTradeNo(), cmd.getTradeNo(), cmd.getAmount(), cmd.getPayload());
            return NotifyOutcome.PAYMENT_NOT_FOUND;
        }

        if (!cmd.isSuccess()) {
            log.info("收到非成功支付通知，不推进：paymentId={}, outTradeNo={}, rawStatus={}",
                    payment.getId(), cmd.getOutTradeNo(), cmd.getRawStatus());
            return NotifyOutcome.NOT_SUCCESS;
        }

        PaymentStatus current = PaymentStatus.of(payment.getStatus());
        if (current == PaymentStatus.SUCCESS) {
            log.info("重复支付通知，幂等忽略：paymentId={}, outTradeNo={}, tradeNo={}",
                    payment.getId(), cmd.getOutTradeNo(), cmd.getTradeNo());
            return NotifyOutcome.IDEMPOTENT;
        }
        if (current != PaymentStatus.PENDING) {
            log.error("【支付告警·需人工介入】支付单已处于 {} 终态却收到成功通知，拒绝推进："
                            + "paymentId={}, outTradeNo={}, tradeNo={}, payload={}",
                    current, payment.getId(), cmd.getOutTradeNo(), cmd.getTradeNo(), cmd.getPayload());
            paymentMapper.saveNotifyPayload(payment.getId(), cmd.getNotifyTime(), truncate(cmd.getPayload()));
            return NotifyOutcome.PAYMENT_NOT_PENDING;
        }

        // 金额必须一致。渠道未回传金额同样视为不一致 —— 宁可人工介入也不能按未知金额确认收款。
        if (cmd.getAmount() == null || !cmd.getAmount().equals(payment.getAmount())) {
            log.error("【支付告警·需人工介入】回调金额与支付单不一致，拒绝推进：paymentId={}, "
                            + "outTradeNo={}, 应付={}分, 回调={}分",
                    payment.getId(), cmd.getOutTradeNo(), payment.getAmount(), cmd.getAmount());
            paymentMapper.saveNotifyPayload(payment.getId(), cmd.getNotifyTime(), truncate(cmd.getPayload()));
            return NotifyOutcome.AMOUNT_MISMATCH;
        }

        VoucherOrder order = voucherOrderMapper.selectById(payment.getOrderId());
        if (order == null || !Objects.equals(order.getStatus(), OrderStatus.UNPAID.getValue())) {
            log.error("【支付告警·需人工介入】订单已不可支付却收到成功通知，拒绝推进（用户可能已付款）："
                            + "orderId={}, orderStatus={}, paymentId={}, outTradeNo={}, tradeNo={}, payload={}",
                    payment.getOrderId(), order == null ? "NOT_FOUND" : order.getStatus(),
                    payment.getId(), cmd.getOutTradeNo(), cmd.getTradeNo(), cmd.getPayload());
            paymentMapper.saveNotifyPayload(payment.getId(), cmd.getNotifyTime(), truncate(cmd.getPayload()));
            return NotifyOutcome.ORDER_NOT_PAYABLE;
        }

        LocalDateTime now = cmd.getNotifyTime() != null ? cmd.getNotifyTime() : LocalDateTime.now();
        int advanced = paymentMapper.advanceStatus(payment.getId(), PaymentStatus.SUCCESS.getValue(),
                cmd.getTradeNo(), now, now, truncate(cmd.getPayload()));
        if (advanced == 0) {
            log.info("支付单已被并发推进，本次按重复通知处理：paymentId={}, outTradeNo={}",
                    payment.getId(), cmd.getOutTradeNo());
            return NotifyOutcome.IDEMPOTENT;
        }

        int payType = cmd.getProvider() == null
                ? PayChannelType.fromCode(payment.getChannel()).getProvider().getOrderPayType()
                : cmd.getProvider().getOrderPayType();
        int paid = voucherOrderMapper.markPaidIfUnpaid(payment.getOrderId(),
                OrderStatus.PAID.getValue(), OrderStatus.UNPAID.getValue(), payType, now);
        if (paid == 0) {
            // 上面刚校验过订单是未支付、且同处一个事务，走到这里说明有并发关单插了进来。
            // 支付单已置成功而订单未推进 = 用户付了钱但订单不可用 → 必须告警。
            log.error("【支付告警·需人工介入】支付单已置成功但订单未能推进（疑似并发关单）："
                            + "orderId={}, paymentId={}, outTradeNo={}",
                    payment.getOrderId(), payment.getId(), cmd.getOutTradeNo());
            return NotifyOutcome.ORDER_NOT_PAYABLE;
        }

        // 只有订单真的被推进了才写 outbox，保证 ORDER_PAID 与订单状态严格一一对应（防重复积分）
        outboxWriter.write("VOUCHER_ORDER", payment.getOrderId(), "ORDER_PAID",
                MqConstants.SECKILL_DIRECT_EXCHANGE, MqConstants.ORDER_PAID_ROUTING_KEY,
                new OrderPaidMessage(order.getUserId(), payment.getOrderId()));
        log.info("支付成功落库：orderId={}, paymentId={}, channel={}, payType={}, tradeNo={}",
                payment.getOrderId(), payment.getId(), payment.getChannel(), payType, cmd.getTradeNo());
        return NotifyOutcome.APPLIED;
    }

    // ==================================================================
    // 关单
    // ==================================================================

    /** 订单超时关单时，把该订单的 PENDING 支付单一起关掉。必须同步关：{@code prepay-ttl(5min) ≤ 订单超时(15min)}
     * 只保证支付单先失效，但失效 ≠ 关闭 —— 只关订单不管支付单，用户仍能拿旧支付串在 5~15min 内付款，回调到达时
     * 订单已是取消态 → 「用户付了钱、订单不可用」。{@code WHERE status = 0} 保证已成功支付单绝不被关。@return 真正被关闭的行数（0 = 没有 PENDING 可关，正常） */
    @Override
    public int closePaymentForOrder(Long orderId) {
        int closed = paymentMapper.markClosedByOrderId(orderId);
        if (closed > 0) {
            log.info("订单超时关单，同步关闭支付单：orderId={}", orderId);
        }
        return closed;
    }

    // ==================================================================
    // 查询
    // ==================================================================
    /** 查支付单（含归属校验）。订单存在但从未发起支付时返回 {@code null} —— 那是正常情况：存量订单都没有支付单，
     * 接口要能被无支付单的订单调用。 */
    @Override
    public Payment query(Long orderId, Long userId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BadRequestException(VoucherConstants.ORDER_NOT_FOUND);
        }
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new ForbiddenException(VoucherConstants.ORDER_ACCESS_DENIED);
        }
        return paymentMapper.selectByOrderId(orderId);
    }

    // ==================================================================
    // 私有工具
    // ==================================================================

    private VoucherOrder requireOwnedUnpaidOrder(Long orderId, Long userId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BadRequestException(VoucherConstants.ORDER_NOT_FOUND);
        }
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new ForbiddenException(VoucherConstants.ORDER_ACCESS_DENIED);
        }
        if (!Objects.equals(order.getStatus(), OrderStatus.UNPAID.getValue())) {
            throw new BadRequestException(VoucherConstants.ORDER_STATUS_INVALID);
        }
        return order;
    }

    /** 解析渠道：留空用默认渠道；非法 code → 400；未配凭据 → 「渠道未配置」（运维问题，与「渠道不支持」
     * 区分开，否则 Nacos 忘了填会表现成渠道不存在）。 */
    private PayChannel resolveChannel(String channelCode) {
        String code = (channelCode == null || channelCode.isBlank())
                ? payProperties.getDefaultChannel() : channelCode;
        PayChannel channel = channelRegistry.get(code);
        if (!channel.isReady()) {
            throw new BizIllegalException(VoucherConstants.PAY_CHANNEL_NOT_CONFIGURED
                    + "（channel=" + channel.code() + "）");
        }
        return channel;
    }

    private Payment createPendingPayment(VoucherOrder order, PayChannel channel) {
        PaymentPricing.Pricing pricing = PaymentPricing.price(voucherMapper.selectById(order.getVoucherId()));
        Payment payment = new Payment()
                .setId(redisIdWorker.nextId("pay"))
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setChannel(channel.code())
                .setAmount(pricing.amount())
                .setFaceValue(pricing.faceValue())
                .setStatus(PaymentStatus.PENDING.getValue())
                .setOutTradeNo(newOutTradeNo());
        if (paymentMapper.insert(payment) != 1) {
            throw new BizIllegalException(VoucherConstants.PREPAY_FAILED + "（支付单落库失败）");
        }
        return payment;
    }

    /** 生成商户单号。与主键同源（Redis 号段），格式 {@code PAY<id>}。轮换时重新取号 —— 不能从 paymentId
     * 派生，否则轮换会得到同一个值。 */
    private String newOutTradeNo() {
        return "PAY" + redisIdWorker.nextId("pay");
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= PAYLOAD_MAX_LENGTH) {
            return s;
        }
        return s.substring(0, PAYLOAD_MAX_LENGTH) + "...[truncated]";
    }
}

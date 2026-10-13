package com.hmdp.voucher.payment.task;

import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.mapper.PaymentMapper;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.PayChannelRegistry;
import com.hmdp.voucher.payment.service.IPaymentService;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.NotifyOutcome;
import com.hmdp.voucher.payment.domain.PayQueryResult;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 主动查单任务：把「回调可能永远不来」的待支付单推到终态。渠道回调是 at-least-once，且现实中会丢；最硬的理由是本项目
 * 部署环境没有公网域名、渠道根本回调不进来。它复用回调那条落地路径（查单成功后构造 {@link PayNotifyCommand} 交给
 * {@link com.hmdp.voucher.payment.service.IPaymentService#applyNotify}），金额校验/幂等/订单可支付性/outbox 只在一条代码路径上。扫描判据 {@code status=PENDING} 且 {@code update_time} 超过 {@code prepay-ttl-minutes}（5min）无活动（不用 create_time：过期后会原地轮换出新商户单号）；本任务不写 FAILED。 */
@Component
@Slf4j
public class PayQueryTask {

    /** 单轮最多处理多少笔：防止某一轮把渠道接口打爆（同一订单的查单频率在渠道侧有限制）。 */
    private static final int SCAN_LIMIT = 200;

    /** 查单来源在 {@code notify_payload} 里的标记。主动查单不是「渠道通知」，留档时标清来源，
     * 否则对账时分不清这条记录是谁写的。 */
    private static final String SOURCE_TAG = "QUERY_TASK";

    private final PaymentMapper paymentMapper;
    private final PayChannelRegistry channelRegistry;
    private final PayProperties payProperties;
    private final IPaymentService paymentService;

    public PayQueryTask(PaymentMapper paymentMapper,
                        PayChannelRegistry channelRegistry,
                        PayProperties payProperties,
                        IPaymentService paymentService) {
        this.paymentMapper = paymentMapper;
        this.channelRegistry = channelRegistry;
        this.payProperties = payProperties;
        this.paymentService = paymentService;
    }

    /** 定时入口。{@code fixedDelay}（上一轮结束后再计时）而不是 {@code fixedRate}：查单依赖外部渠道、耗时不可控，
     * 用固定频率会互相叠加。 */
    @Scheduled(fixedDelayString = "${hmdp.pay.query-task-interval-millis:"
            + PayProperties.DEFAULT_QUERY_INTERVAL_MILLIS + "}")
    public void scheduledQuery() {
        try {
            queryOnce();
        } catch (RuntimeException e) {
            // 任务绝不因为一轮异常而停摆：吞掉并记日志，下一轮继续
            log.error("主动查单任务异常（下一轮继续）：err={}", e.getMessage(), e);
        }
    }

    /** 执行一轮查单（与调度解耦，便于单测直接调用）。
     * @return 本轮真的调了渠道的支付单数量 */
    public int queryOnce() {
        List<Payment> stale = paymentMapper.selectStalePending(
                payProperties.getPrepayTtlMinutes(), SCAN_LIMIT);
        if (stale == null || stale.isEmpty()) {
            return 0;
        }
        log.info("主动查单：扫到 {} 笔停留超过 {} 分钟的待支付单",
                stale.size(), payProperties.getPrepayTtlMinutes());

        int queried = 0;
        for (Payment payment : stale) {
            try {
                if (queryOne(payment)) {
                    queried++;
                }
            } catch (RuntimeException e) {
                // 单笔失败不能影响整批：否则一条脏数据会让其余订单永远查不到
                log.warn("主动查单单笔失败，跳过：paymentId={}, outTradeNo={}, err={}",
                        payment.getId(), payment.getOutTradeNo(), e.getMessage());
            }
        }
        return queried;
    }

    private boolean queryOne(Payment payment) {
        PayChannel channel = channelRegistry.find(payment.getChannel());
        if (channel == null) {
            log.error("待支付单的渠道无法识别，跳过（疑似脏数据）：paymentId={}, channel={}",
                    payment.getId(), payment.getChannel());
            return false;
        }
        if (!channel.isReady()) {
            // 凭据没配好，等配好下一轮再查。这里用 debug：Nacos 尚未配置时不该刷满告警
            log.debug("渠道未配置，跳过主动查单：channel={}, paymentId={}", channel.code(), payment.getId());
            return false;
        }

        PayQueryResult result = channel.query(payment);
        if (result == null || result.getStatus() != PaymentStatus.SUCCESS) {
            // 仍未支付 / 渠道侧已关闭：都不由本任务改状态。
            // 「已关闭 / 失败」不做终态处理的理由见 PaymentServiceImpl 类注释（本期不写 FAILED）。
            log.debug("主动查单结论非成功，不推进：paymentId={}, rawStatus={}",
                    payment.getId(), result == null ? null : result.getRawStatus());
            return true;
        }

        NotifyOutcome outcome = paymentService.applyNotify(toCommand(payment, channel, result));
        log.info("主动查单推进支付：paymentId={}, outTradeNo={}, outcome={}",
                payment.getId(), payment.getOutTradeNo(), outcome);
        return true;
    }

    /** 把查单结果包装成与「渠道通知」同构的命令。金额一定要带上：{@code applyNotify} 会拿它与支付单比对，不一致就
     * 拒绝推进并告警 —— 主动查单这条通道同样不能凭「未知金额」确认收款。 */
    private PayNotifyCommand toCommand(Payment payment, PayChannel channel, PayQueryResult result) {
        return new PayNotifyCommand()
                .setProvider(channel.type().getProvider())
                .setOutTradeNo(payment.getOutTradeNo())
                .setTradeNo(result.getTradeNo())
                .setAmount(result.getAmount())
                .setSuccess(true)
                .setRawStatus(result.getRawStatus())
                .setPayload(sourceTag(result))
                .setNotifyTime(LocalDateTime.now());
    }

    /** 查单不是渠道通知，没有「原始报文」。这里合成一段可读的来源说明落进 {@code notify_payload}，让对账时能分清
     * 「这行是谁写的、当时渠道说了什么」。 */
    private String sourceTag(PayQueryResult result) {
        return SOURCE_TAG + "|rawStatus=" + result.getRawStatus()
                + "|tradeNo=" + result.getTradeNo()
                + "|amountFen=" + result.getAmount();
    }
}

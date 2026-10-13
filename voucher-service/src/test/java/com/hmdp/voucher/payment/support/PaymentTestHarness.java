package com.hmdp.voucher.payment.support;

import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.RedisIdWorker;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Payment;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.payment.PayChannel;
import com.hmdp.voucher.payment.PayChannelRegistry;
import com.hmdp.voucher.payment.service.impl.PaymentServiceImpl;
import com.hmdp.voucher.payment.config.PayProperties;
import com.hmdp.voucher.payment.domain.PayProvider;
import com.hmdp.voucher.payment.domain.PaymentStatus;
import com.hmdp.voucher.payment.dto.PayNotifyCommand;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 支付单测的统一装配台：把 {@link com.hmdp.voucher.payment.service.impl.PaymentServiceImpl} 用「内存态假 Mapper + 假渠道 +
 * 两个 Mockito 替身」拼起来，<b>不起 Spring、不连库、不联网</b>。
 *
 * <p>为什么需要它：4 个测试类要构造同一套协作者，各自手写会漂移
 * （有人改了渠道 code、有人忘了推进假 DB 时钟）。收敛到一处后，
 * 「本测试环境长什么样」只有一份定义。
 *
 * <p>两个 Mockito 替身的选择理由：
 * <ul>
 *   <li>{@link RedisIdWorker} —— 真实现要连 Redis 取号段，这里只需单调递增的号；</li>
 *   <li>{@link OutboxWriter} —— 真实现要写 {@code business_outbox} 表，
 *       而「outbox 只写了一条」正是要断言的，用 Mockito 直接数调用次数最直接。</li>
 * </ul>
 */
public class PaymentTestHarness {

    public static final String CHANNEL_CODE = "mock";

    public final InMemoryPaymentMapper paymentMapper = new InMemoryPaymentMapper();
    public final InMemoryVoucherOrderMapper orderMapper = new InMemoryVoucherOrderMapper();
    public final VoucherMapperStub voucherMapper = new VoucherMapperStub();
    public final RecordingChannel channel = new RecordingChannel(CHANNEL_CODE);
    public final OutboxWriter outboxWriter = mock(OutboxWriter.class);
    public final RedisIdWorker idWorker = mock(RedisIdWorker.class);
    public final PayProperties payProperties = new PayProperties();
    public final PayChannelRegistry registry;
    public final PaymentServiceImpl service;

    private final AtomicLong idSeq = new AtomicLong(1_000L);

    public PaymentTestHarness() {
        this(new PayChannel[0]);
    }

    /**
     * @param extraChannels 额外注册的渠道（用于「换渠道被拒」这类需要两条渠道的用例）
     */
    public PaymentTestHarness(PayChannel... extraChannels) {
        when(idWorker.nextId(anyString())).thenAnswer(inv -> idSeq.incrementAndGet());
        // 默认渠道指向测试渠道，避免用例不传 channel 时撞上「alipay_page 未注册」
        payProperties.setDefaultChannel(CHANNEL_CODE);
        List<PayChannel> all = new ArrayList<>();
        all.add(channel);
        all.addAll(Arrays.asList(extraChannels));
        registry = new PayChannelRegistry(all);
        service = new PaymentServiceImpl(paymentMapper, orderMapper, voucherMapper, registry,
                payProperties, idWorker, outboxWriter);
    }

    // ---------------- 造数据 ----------------

    /** 造一张面值 100 元、售价 10 元的券（金额单位：分） */
    public Voucher voucher(long id, long payValue, long actualValue) {
        Voucher v = new Voucher();
        v.setId(id);
        v.setPayValue(payValue);
        v.setActualValue(actualValue);
        voucherMapper.put(v);
        return v;
    }

    /** 造一张默认券（面值 10000 分 = 100 元，售价 1000 分 = 10 元） */
    public Voucher defaultVoucher() {
        return voucher(9001L, 1000L, 10000L);
    }

    public VoucherOrder order(long id, long userId, OrderStatus status, long voucherId) {
        VoucherOrder o = new VoucherOrder();
        o.setId(id);
        o.setUserId(userId);
        o.setVoucherId(voucherId);
        o.setStatus(status.getValue());
        orderMapper.put(o);
        return o;
    }

    /** 造一张「未支付」的订单（配默认券） */
    public VoucherOrder unpaidOrder(long orderId, long userId) {
        defaultVoucher();
        return order(orderId, userId, OrderStatus.UNPAID, 9001L);
    }

    /** 构造一条「支付成功」的通知命令 */
    public PayNotifyCommand successNotify(Payment payment) {
        return successNotify(payment, payment.getAmount());
    }

    public PayNotifyCommand successNotify(Payment payment, Long amount) {
        return new PayNotifyCommand()
                .setProvider(PayProvider.ALIPAY)
                .setOutTradeNo(payment.getOutTradeNo())
                .setTradeNo("TRADE-" + payment.getOutTradeNo())
                .setAmount(amount)
                .setSuccess(true)
                .setRawStatus("TRADE_SUCCESS")
                .setPayload("{\"out_trade_no\":\"" + payment.getOutTradeNo() + "\"}")
                .setNotifyTime(LocalDateTime.of(2026, 9, 26, 12, 0));
    }

    /** 造一条「未支付成功」的通知（如 WAIT_BUYER_PAY） */
    public PayNotifyCommand notSuccessNotify(Payment payment) {
        return new PayNotifyCommand()
                .setProvider(PayProvider.ALIPAY)
                .setOutTradeNo(payment.getOutTradeNo())
                .setSuccess(false)
                .setRawStatus("WAIT_BUYER_PAY")
                .setPayload("{\"trade_status\":\"WAIT_BUYER_PAY\"}")
                .setNotifyTime(LocalDateTime.of(2026, 9, 26, 12, 0));
    }

    /** 直接塞一张 PENDING 支付单（模拟已发起过支付） */
    public Payment pendingPayment(long orderId, long userId, String outTradeNo, long amount, long faceValue) {
        Payment p = new Payment()
                .setId(idSeq.incrementAndGet())
                .setOrderId(orderId)
                .setUserId(userId)
                .setChannel(CHANNEL_CODE)
                .setAmount(amount)
                .setFaceValue(faceValue)
                .setStatus(PaymentStatus.PENDING.getValue())
                .setOutTradeNo(outTradeNo);
        paymentMapper.put(p);
        return p;
    }

    public List<String> channelCodes() {
        return List.copyOf(registry.codes());
    }
}

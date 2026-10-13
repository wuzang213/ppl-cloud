package com.hmdp.voucher.service;

import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.mapper.SeckillVoucherMapper;
import com.hmdp.voucher.mapper.VoucherOrderMapper;
import com.hmdp.voucher.payment.service.IPaymentService;
import com.hmdp.voucher.payment.support.VoucherMapperStub;
import com.hmdp.voucher.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「只有秒杀券才回补秒杀库存」的守卫单测（{@code specs/0018} 验收 4）。
 *
 * <p>背景（属"写错不报错、只在行为上悄悄坏掉"的一类）：{@code refundOrder} 与
 * {@code closeTimeoutOrder} 原本**无条件**执行 {@code stock = stock + 1} 并写
 * {@code SECKILL_STOCK_RESTORE} outbox。普通代金券（{@code type != 1}）没有
 * {@code tb_seckill_voucher} 行，{@code update} 影响 0 行**不报错**，却会**多发一条库存回补事件**。
 *
 * <p>因此这里钉住两条不变式：
 * <ul>
 *   <li><b>普通券</b>：退款 / 超时关单**零库存副作用**（不碰秒杀券商、不发回补事件）；</li>
 *   <li><b>秒杀券</b>：行为与现状一致（回归，防"一刀切删掉回补"）。</li>
 * </ul>
 *
 * <p>装配方式与 {@code payment.support.PaymentTestHarness} 一致：<b>不起 Spring、不连库、不联网</b>。
 * {@code VoucherOrderServiceImpl} 继承 MyBatis-Plus {@code ServiceImpl} 且用字段注入，
 * 故用 {@link ReflectionTestUtils} 注入协作方（含 {@code baseMapper}）。
 */
class NormalVoucherStockRestoreTest {

    private static final Long ORDER_ID = 30001L;
    private static final Long VOUCHER_ID = 70001L;

    private VoucherOrderMapper orderMapper;
    private VoucherMapperStub voucherMapper;
    private ISeckillVoucherService seckillVoucherService;
    private OutboxWriter outboxWriter;
    private IPaymentService paymentService;
    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(VoucherOrderMapper.class);
        voucherMapper = new VoucherMapperStub();
        // 秒杀券库存链路是 MyBatis-Plus 链式 update()：给一条「真链 + 假 BaseMapper」，
        // 让 setSql().eq().update() 能真实走完（末端落到假 Mapper，返回 0 即可）；断言只看"有没有调 update()"。
        seckillVoucherService = mock(ISeckillVoucherService.class);
        when(seckillVoucherService.update())
                .thenReturn(ChainWrappers.updateChain(mock(SeckillVoucherMapper.class)));
        outboxWriter = mock(OutboxWriter.class);
        paymentService = mock(IPaymentService.class);

        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "outboxWriter", outboxWriter);
        ReflectionTestUtils.setField(service, "paymentService", paymentService);
        ReflectionTestUtils.setField(service, "voucherMapper", voucherMapper);
    }

    /** 造一张指定类型的券 + 一张指定状态的订单（并让 getById 能查回来） */
    private void givenOrder(int voucherType, OrderStatus status) {
        Voucher voucher = new Voucher();
        voucher.setId(VOUCHER_ID);
        voucher.setType(voucherType);
        voucherMapper.put(voucher);

        VoucherOrder order = new VoucherOrder();
        order.setId(ORDER_ID);
        order.setUserId(1L);
        order.setVoucherId(VOUCHER_ID);
        order.setStatus(status.getValue());
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
    }

    // ==================== 普通券：零库存副作用 ====================

    @Test
    @DisplayName("普通券退款：不回补秒杀库存、不发 SECKILL_STOCK_RESTORE（type != 1）")
    void refundNormalVoucherHasNoStockSideEffect() {
        givenOrder(0, OrderStatus.PAID);

        service.refundOrder(ORDER_ID);

        verify(seckillVoucherService, never()).update();
        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("普通券超时关单：不回补秒杀库存、不发回补事件，但仍要关掉 PENDING 支付单")
    void closeTimeoutNormalVoucherHasNoStockSideEffect() {
        givenOrder(0, OrderStatus.UNPAID);

        service.closeTimeoutOrder(ORDER_ID);

        verify(seckillVoucherService, never()).update();
        verifyNoInteractions(outboxWriter);
        verify(paymentService, times(1)).closePaymentForOrder(ORDER_ID);
    }

    // ==================== 秒杀券：行为不变（回归） ====================

    @Test
    @DisplayName("秒杀券退款：仍回补库存并发 SECKILL_STOCK_RESTORE（type == 1，回归）")
    void refundSeckillVoucherStillRestoresStock() {
        givenOrder(1, OrderStatus.PAID);

        service.refundOrder(ORDER_ID);

        verify(seckillVoucherService, times(1)).update();
        verify(outboxWriter, times(1)).write(eq("VOUCHER"), eq(VOUCHER_ID), eq("SECKILL_STOCK_RESTORE"), any());
    }

    @Test
    @DisplayName("秒杀券超时关单：仍回补库存并发回补事件，且关掉 PENDING 支付单（type == 1，回归）")
    void closeTimeoutSeckillVoucherStillRestoresStock() {
        givenOrder(1, OrderStatus.UNPAID);

        service.closeTimeoutOrder(ORDER_ID);

        verify(seckillVoucherService, times(1)).update();
        verify(outboxWriter, times(1)).write(eq("VOUCHER"), eq(VOUCHER_ID), eq("SECKILL_STOCK_RESTORE"), any());
        verify(paymentService, times(1)).closePaymentForOrder(ORDER_ID);
    }
}
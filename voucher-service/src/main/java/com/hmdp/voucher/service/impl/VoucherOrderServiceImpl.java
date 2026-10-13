package com.hmdp.voucher.service.impl;

import static com.hmdp.common.constants.MqConstants.SECKILL_DIRECT_EXCHANGE;
import static com.hmdp.common.constants.MqConstants.SECKILL_ORDER_ROUTING_KEY;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.common.utils.RedisIdWorker;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.BizIllegalException;
import com.hmdp.common.exception.ForbiddenException;
import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.Voucher;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.domain.VoucherOrderDetailVO;
import com.hmdp.voucher.mapper.VoucherMapper;
import com.hmdp.voucher.mapper.VoucherOrderMapper;
import com.hmdp.voucher.payment.service.IPaymentService;
import com.hmdp.voucher.payment.dto.PaymentVO;
import com.hmdp.voucher.service.ISeckillVoucherService;
import com.hmdp.voucher.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitMqHelper rabbitMqHelper;
    @Resource
    private OutboxWriter outboxWriter;
    @Resource
    private IPaymentService paymentService;
    @Resource
    private VoucherMapper voucherMapper;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        if (result == null) {
            throw new BadRequestException("下单失败，请重试");
        }

        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            throw new BadRequestException(r == 1 ? VoucherConstants.STOCK_NOT_ENOUGH : VoucherConstants.DUPLICATE_ORDER);
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 3.发送到RabbitMQ异步处理（带确认和重试）
        try {
            rabbitMqHelper.sendMessageWithConfirm(SECKILL_DIRECT_EXCHANGE, SECKILL_ORDER_ROUTING_KEY, voucherOrder, MqConstants.MQ_RETRY_TIMES);
        } catch (Exception e) {
            String stockKey = "seckill:{" + voucherId + "}:stock";
            String orderKey = "seckill:{" + voucherId + "}:order";
            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
            throw e;
        }
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("用户已经购买过了");
            return false;
        }
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return false;
        }
        boolean saved = save(voucherOrder);
        if (!saved) {
            throw new RuntimeException("order save failed, voucherId=" + voucherOrder.getVoucherId());
        }
        // 订单超时延迟关闭改为写 outbox：Canal 投递到「延迟交换机 + 延迟队列」，靠队列级 x-message-ttl(15min) 实现延迟、
        // TTL 到期死信转发到 order.timeout.queue；有记录可对账重试，不依赖请求线程内的 afterCommit（失败即丢失），也不依赖
        // RabbitMQ delayed 插件（普通 direct 交换机会静默忽略 setDelay，导致立即关单）。
        outboxWriter.write("VOUCHER_ORDER", voucherOrder.getId(), "ORDER_TIMEOUT_SCHEDULED",
                MqConstants.ORDER_TIMEOUT_DELAY_EXCHANGE, MqConstants.ORDER_TIMEOUT_DELAY_ROUTING_KEY,
                voucherOrder.getId());
        return true;
    }

    /**
     * 普通代金券下单：与秒杀券不同，普通券**无库存、无时间窗**，直接同步建一条未支付订单即可，
     * 之后支付 / 核销 / 退款全部复用既有链路（{@code /pay/{id}}、{@code /use/{id}}、{@code /refund/{id}}）。
     *
     * <p>三条判据：
     * ① 券必须存在且已上架（{@code status == 1}，与店铺券列表同口径）；
     * ② {@code type == 1} 的秒杀券必须走秒杀入口（{@code /voucher-order/seckill/{id}}），此处拒绝；
     * ③ 普通券**不做一人一券**（无数量上限、可重复购买）——这是与秒杀券的关键区别。
     *
     * @param voucherId 代金券 id
     * @return {@code Result.ok(orderId)} 新建订单 id
     */
    @Override
    @Transactional
    public Result buyNormalVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null || voucher.getStatus() == null || voucher.getStatus() != 1) {
            throw new BadRequestException(VoucherConstants.VOUCHER_NOT_FOUND);
        }
        if (voucher.getType() != null && voucher.getType() == 1) {
            throw new BadRequestException(VoucherConstants.SECKILL_VOUCHER_WRONG_PATH);
        }
        long orderId = redisIdWorker.nextId("order");
        LocalDateTime now = LocalDateTime.now();
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(OrderStatus.UNPAID.getValue());
        voucherOrder.setCreateTime(now);
        voucherOrder.setUpdateTime(now);
        if (!save(voucherOrder)) {
            throw new BizIllegalException(VoucherConstants.ORDER_CREATE_FAILED + "（voucherId=" + voucherId + "）");
        }
        // 与秒杀券同一套超时关单：延迟交换机 + 队列级 TTL，到期死信转发到 order.timeout.queue
        outboxWriter.write("VOUCHER_ORDER", voucherOrder.getId(), "ORDER_TIMEOUT_SCHEDULED",
                MqConstants.ORDER_TIMEOUT_DELAY_EXCHANGE, MqConstants.ORDER_TIMEOUT_DELAY_ROUTING_KEY,
                voucherOrder.getId());
        return Result.ok(orderId);
    }

    @Override
    public Result queryOrderById(Long id) {
        VoucherOrder order = getById(id);
        if (order == null) {
            throw new BadRequestException(VoucherConstants.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(UserHolder.getUser().getId())) {
            throw new ForbiddenException(VoucherConstants.ORDER_ACCESS_DENIED);
        }
        // 返回值「只增」一个 payment 字段：其余字段与原来逐字段一致（见 VoucherOrderDetailVO 注释）
        return Result.ok(VoucherOrderDetailVO.of(order,
                PaymentVO.of(paymentService.query(id, order.getUserId()))));
    }

    @Override
    public Result queryOrderStatus(Long id) {
        VoucherOrder order = getById(id);
        if (order == null) {
            throw new BadRequestException(VoucherConstants.ORDER_NOT_FOUND);
        }
        Long userId = UserHolder.getUser().getId();       // 依赖 @LoginRequired 已生效
        if (!order.getUserId().equals(userId)) {
            throw new BadRequestException(VoucherConstants.ORDER_ACCESS_DENIED);
        }
        return Result.ok(order.getStatus());
    }

    @Override
    public Result queryOrderList(Integer current) {
        Page<VoucherOrder> page = query()
                .eq("user_id", UserHolder.getUser().getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    /** 发起支付：交给支付领域服务建（或复用）支付单并返回渠道支付串。这里刻意什么都不做 —— 不读订单状态、不改状态、不写
     * outbox；归属校验、订单可支付性、渠道解析、支付单复用/轮换、金额计算全在 {@link com.hmdp.voucher.payment.service.IPaymentService#prepay} 一处，避免
     * 「Controller 放行、Service 又放行一遍」这类校验漂移。订单转为已支付只在回调/查单路径发生。 */
    @Override
    public Result payOrder(Long id, String channel, String clientIp) {
        return Result.ok(paymentService.prepay(id, channel, currentUserId(), clientIp));
    }

    @Override
    public Result queryPayment(Long id) {
        return Result.ok(PaymentVO.of(paymentService.query(id, currentUserId())));
    }

    private static Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new ForbiddenException(VoucherConstants.ORDER_ACCESS_DENIED);
        }
        return user.getId();
    }

    @Override
    @Transactional
    public Result useOrder(Long id) {
        VoucherOrder order = getById(id);
        if (order == null || order.getStatus() != OrderStatus.PAID.getValue()) {
            throw new BadRequestException(VoucherConstants.ORDER_STATUS_INVALID);
        }
        update().eq("id", id)
                .set("status", OrderStatus.USED.getValue())
                .set("use_time", LocalDateTime.now())
                .update();
        return Result.ok();
    }

    @Override
    @Transactional
    public Result refundOrder(Long id) {
        VoucherOrder order = getById(id);
        if (order == null || order.getStatus() != OrderStatus.PAID.getValue()) {
            throw new BadRequestException(VoucherConstants.ORDER_STATUS_INVALID);
        }
        update().eq("id", id)
                .set("status", OrderStatus.REFUNDED.getValue())
                .set("refund_time", LocalDateTime.now())
                .update();
        // 🔴 仅秒杀券回补库存：普通券（type != 1）无 tb_seckill_voucher 行，无条件回补会"update 影响 0 行"
        //    却不报错，还会多发一条 SECKILL_STOCK_RESTORE 事件 —— 属"写错不报错、只在行为上悄悄坏掉"。
        if (isSeckillVoucher(order.getVoucherId())) {
            seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", order.getVoucherId())
                    .update();
            // 秒杀库存回补改为写 outbox，由 VoucherCacheListener 按订单去重消费
            outboxWriter.write("VOUCHER", order.getVoucherId(), "SECKILL_STOCK_RESTORE",
                    stockRestoreMessage(order.getVoucherId(), id));
        }
        return Result.ok();
    }

    @Override
    public Result merchantOrders(Long shopId, Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new ForbiddenException(VoucherConstants.PERMISSION_DENIED);
        }
        Page<VoucherOrder> page = query()
                .inSql("voucher_id", "select id from tb_voucher where shop_id = " + shopId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    @Transactional
    public void closeTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() != OrderStatus.UNPAID.getValue()) {
            return;
        }
        update().eq("id", orderId)
                .set("status", OrderStatus.CANCELED.getValue())
                .update();
        // 🔴 同样只回补秒杀券库存：普通券超时关单不得触碰 tb_seckill_voucher，也不得发回补事件。
        if (isSeckillVoucher(order.getVoucherId())) {
            seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", order.getVoucherId())
                    .update();
            // 超时关单的库存回补同样写 outbox，按订单去重消费
            outboxWriter.write("VOUCHER", order.getVoucherId(), "SECKILL_STOCK_RESTORE",
                    stockRestoreMessage(order.getVoucherId(), orderId));
        }
        // 追加：把该订单的 PENDING 支付单一起关掉。只关 PENDING（条件更新），已成功的支付单绝不会被这里覆盖；
        // 否则「订单已取消、支付单还有效」会让用户在关单后仍能付款成功 → 用户付了钱订单不可用。
        // 秒杀主逻辑（订单状态 + 库存回补 + outbox）保持原样，不受影响。
        paymentService.closePaymentForOrder(orderId);
    }

    /**
     * 判断某张券是否为秒杀券（{@code type == 1}，hm-dianping 约定；与 agent 侧 {@code prepare_order_card} 同一口径）。
     * 只有秒杀券才有 {@code tb_seckill_voucher} 库存行，退款 / 超时关单才需要回补库存。
     */
    private boolean isSeckillVoucher(Long voucherId) {
        Voucher voucher = voucherMapper.selectById(voucherId);
        return voucher != null && voucher.getType() != null && voucher.getType() == 1;
    }

    /**
     * 构造秒杀库存回补消息：携带 orderId 供消费端做订单级去重，避免重投导致库存虚高。
     */
    private CacheSyncMessage stockRestoreMessage(Long voucherId, Long orderId) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", orderId);
        CacheSyncMessage msg = new CacheSyncMessage("VOUCHER", voucherId, null, "SECKILL_STOCK_RESTORE");
        msg.setData(data);
        return msg;
    }
}

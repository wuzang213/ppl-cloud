package com.hmdp.voucher.service.impl;

import static com.hmdp.common.constants.MqConstants.SECKILL_DIRECT_EXCHANGE;
import static com.hmdp.common.constants.MqConstants.SECKILL_ORDER_ROUTING_KEY;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.OrderPaidMessage;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.outbox.OutboxWriter;
import com.hmdp.common.utils.RabbitMqHelper;
import com.hmdp.common.utils.RedisIdWorker;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.exception.BadRequestException;
import com.hmdp.common.exception.ForbiddenException;
import com.hmdp.voucher.constants.VoucherConstants;
import com.hmdp.common.utils.UserHolder;
import com.hmdp.voucher.domain.OrderStatus;
import com.hmdp.voucher.domain.VoucherOrder;
import com.hmdp.voucher.mapper.VoucherOrderMapper;
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
        // 订单超时延迟关闭改为写 outbox：由 Canal 投递到「延迟交换机 + 延迟队列」，
        // 靠队列级 x-message-ttl(15min) 实现延迟，TTL 到期死信转发到 order.timeout.queue。
        // 有记录可对账重试，不再依赖请求线程内的 afterCommit（失败即丢失），
        // 也不依赖 RabbitMQ delayed 插件（普通 direct 交换机会静默忽略 setDelay，导致立即关单）。
        outboxWriter.write("VOUCHER_ORDER", voucherOrder.getId(), "ORDER_TIMEOUT_SCHEDULED",
                MqConstants.ORDER_TIMEOUT_DELAY_EXCHANGE, MqConstants.ORDER_TIMEOUT_DELAY_ROUTING_KEY,
                voucherOrder.getId());
        return true;
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
        return Result.ok(order);
    }

    @Override
    public Result queryOrderStatus(Long id) {
        VoucherOrder order = getById(id);
        if (order == null) {
            throw new BadRequestException(VoucherConstants.ORDER_NOT_FOUND);
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

    @Override
    @Transactional
    public Result payOrder(Long id) {
        VoucherOrder order = getById(id);
        if (order == null || order.getStatus() != OrderStatus.UNPAID.getValue()) {
            throw new BadRequestException(VoucherConstants.ORDER_STATUS_INVALID);
        }
        update().eq("id", id)
                .set("status", OrderStatus.PAID.getValue())
                .set("pay_time", LocalDateTime.now())
                .update();
        // 支付成功消息改为写 outbox：可靠投递到用户积分队列，失败可对账重试
        outboxWriter.write("VOUCHER_ORDER", id, "ORDER_PAID",
                SECKILL_DIRECT_EXCHANGE, MqConstants.ORDER_PAID_ROUTING_KEY,
                new OrderPaidMessage(order.getUserId(), id));
        return Result.ok();
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
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        // 秒杀库存回补改为写 outbox，由 VoucherCacheListener 按订单去重消费
        outboxWriter.write("VOUCHER", order.getVoucherId(), "SECKILL_STOCK_RESTORE",
                stockRestoreMessage(order.getVoucherId(), id));
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
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        // 超时关单的库存回补同样写 outbox，按订单去重消费
        outboxWriter.write("VOUCHER", order.getVoucherId(), "SECKILL_STOCK_RESTORE",
                stockRestoreMessage(order.getVoucherId(), orderId));
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

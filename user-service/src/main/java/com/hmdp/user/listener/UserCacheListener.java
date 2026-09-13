package com.hmdp.user.listener;

import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxConsumeService;
import com.hmdp.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务 outbox 事件监听器：订阅缓存同步 fanout 广播，执行签到积分等副作用。
 * <p>
 * 原 afterCommit 中的积分累加已迁移至此，由 outbox + MQ 保证投递，失败可对账重试。
 */
@Slf4j
@Component
public class UserCacheListener {

    @Resource
    private OutboxConsumeService outboxConsumeService;

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = CacheConstants.QUEUE_USER_CACHE,
                    durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")
            ),
            exchange = @Exchange(
                    name = CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE,
                    type = "fanout"
            )
    ))
    public void handleCacheSync(CacheSyncMessage message, Message amqp) {
        log.debug("UserCacheListener 收到缓存同步消息，type={}, eventType={}",
                message.getType(), message.getEventType());
        if (!outboxConsumeService.tryMarkConsumed(amqp.getMessageProperties().getMessageId())) {
            return;
        }
        if (!"USER_SIGNED".equals(message.getEventType())) {
            log.debug("UserCacheListener 忽略事件：{}", message.getEventType());
            return;
        }
        Long userId = message.getId();
        String signDate = message.getData() == null ? null : String.valueOf(message.getData().get("signDate"));
        // 积分累加必须幂等：按 用户 + 签到日期 去重，避免 outbox 重投导致重复加分
        String dedupKey = "user:credits:sign:" + userId + ":" + signDate;
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 7, TimeUnit.DAYS);
        if (Boolean.TRUE.equals(first)) {
            userService.addSignCredits(userId);
            log.debug("签到积分已补偿，userId={}, signDate={}", userId, signDate);
        } else {
            log.debug("签到积分已补偿过，跳过。userId={}, signDate={}", userId, signDate);
        }
    }
}

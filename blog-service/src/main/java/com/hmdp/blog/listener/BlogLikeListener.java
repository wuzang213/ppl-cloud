package com.hmdp.blog.listener;

import com.hmdp.blog.domain.BlogLikeMessage;
import com.hmdp.common.constants.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

import static com.hmdp.common.constants.RedisConstants.BLOG_LIKED_KEY;

/**
 * 博文点赞消息监听器。
 * <p>
 * 只负责维护点赞关系的 Redis ZSet。点赞通知已由 {@code BlogServiceImpl.likeBlog}
 * 直接写入第二条 outbox 事件（{@code BLOG_NOTICE} → notice.direct）投递，
 * 与 {@code FollowServiceImpl.follow} 的 {@code FOLLOW_NOTICE} 写法对称，此处不再二次转发。
 * <p>
 * 无需 messageId 幂等去重：ZSet 的 add / remove 本身幂等，重复消费结果一致。
 */
@Slf4j
@Component
public class BlogLikeListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.BLOG_LIKE_QUEUE, durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")),
            exchange = @Exchange(name = MqConstants.BLOG_LIKE_DIRECT_EXCHANGE, type = "direct"),
            key = MqConstants.BLOG_LIKE_ROUTING_KEY
    ))
    public void handleBlogLike(BlogLikeMessage message) {
        if (message == null || message.getBlogId() == null || message.getUserId() == null) {
            log.warn("invalid blog like message: {}", message);
            return;
        }
        String key = BLOG_LIKED_KEY + message.getBlogId();
        if (Boolean.TRUE.equals(message.getLiked())) {
            stringRedisTemplate.opsForZSet().add(key, message.getUserId().toString(), System.currentTimeMillis());
        } else {
            stringRedisTemplate.opsForZSet().remove(key, message.getUserId().toString());
        }
        log.debug("sync blog like to redis, blogId={}, userId={}, liked={}",
                message.getBlogId(), message.getUserId(), message.getLiked());
    }
}

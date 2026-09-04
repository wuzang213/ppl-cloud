package com.hmdp.blog.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.blog.domain.Blog;
import com.hmdp.common.constants.CacheConstants;
import com.hmdp.common.domain.CacheSyncMessage;
import com.hmdp.common.outbox.OutboxConsumeService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.hmdp.common.constants.RedisConstants.CACHE_BLOG_HOT_KEY;
import static com.hmdp.common.constants.RedisConstants.CACHE_BLOG_KEY;


/**
 * 博客缓存监听器
 */
@Slf4j
@Component
public class BlogCacheListener {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OutboxConsumeService outboxConsumeService;

    @Resource
    private Cache<Integer, List<Blog>> blogHotCache;

    @Resource
    private Cache<Long, Blog> blogCache;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(
                    name = CacheConstants.QUEUE_BLOG_CACHE,
                    durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")
            ),
            exchange = @Exchange(
                    name = CacheConstants.CACHE_SYNC_FANOUT_EXCHANGE,
                    type = "fanout"
            )
    ))
    public void handleCacheSync(CacheSyncMessage message, Message amqp) {
        log.debug("BlogCacheListener 收到缓存同步消息，type={}", message.getType());
        if (!outboxConsumeService.tryMarkConsumed(amqp.getMessageProperties().getMessageId())) {
            return;
        }
        if (!"BLOG".equals(message.getType())) {
            log.debug("BlogCacheListener 忽略消息类型：{}", message.getType());
            return;
        }
        Long blogId = message.getId();
        blogCache.invalidate(blogId);
        blogHotCache.invalidateAll();
        deleteRedisKeysByPattern(CACHE_BLOG_HOT_KEY + "*");
        stringRedisTemplate.delete(CACHE_BLOG_KEY + blogId);
        log.debug("已清除Blog缓存，id={}", blogId);
    }

    /**
     * 删除 Redis 中匹配指定模式的键
     * @param pattern
     */
    private void deleteRedisKeysByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> matched = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(200).build())) {
                while (cursor.hasNext()) {
                    matched.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return matched;
        });
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }
}
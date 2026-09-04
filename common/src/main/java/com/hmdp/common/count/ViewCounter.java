package com.hmdp.common.count;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 实时浏览计数：请求只做有界内存入队，后台定时批量 Pipeline 刷 Redis。
 */
@Slf4j
@Component
public class ViewCounter {

    private static final int QUEUE_CAPACITY = 100_000;
    private static final int BATCH_SIZE = 1_000;
    private static final int PIPELINE_CHUNK_SIZE = 200;
    private static final int MAX_RETRY_TIMES = 3;

    private static final DefaultRedisScript<List> DRAIN_SCRIPT = new DefaultRedisScript<>(
            "local t = redis.call('HGETALL', KEYS[1]); "
            + "if next(t) then redis.call('DEL', KEYS[1]) end; "
            + "return t;",
            List.class);

    private final LinkedBlockingQueue<ViewEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedCount = new AtomicLong();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 请求线程只做非阻塞入队，绝不阻塞、绝不直接碰 Redis。
     */
    public void asyncIncr(String hashKey, Long id) {
        if (!queue.offer(new ViewEvent(hashKey, id))) {
            droppedCount.incrementAndGet();
            log.warn("浏览量异步队列已满，丢弃事件 hashKey={}, id={}", hashKey, id);
        }
    }

    public void incr(String hashKey, Long id) {
        stringRedisTemplate.opsForHash().increment(hashKey, id.toString(), 1);
    }

    public List<Object> takeAndReset(String hashKey) {
        return stringRedisTemplate.execute(DRAIN_SCRIPT, Collections.singletonList(hashKey));
    }

    @Scheduled(fixedDelay = 200, initialDelay = 1_000)
    public void flushBatch() {
        List<ViewEvent> batch = new ArrayList<>(BATCH_SIZE);
        int count = queue.drainTo(batch, BATCH_SIZE);
        if (count == 0) {
            return;
        }
        int failedCount = 0;
        for (int from = 0; from < batch.size(); from += PIPELINE_CHUNK_SIZE) {
            int to = Math.min(from + PIPELINE_CHUNK_SIZE, batch.size());
            failedCount += writeChunk(batch.subList(from, to));
        }
        if (failedCount > 0) {
            log.warn("浏览量批量写入 Redis 完成，失败待重试 {} 条，队列剩余 {} 条",
                    failedCount, queue.size());
        }
    }

    private int writeChunk(List<ViewEvent> events) {
        List<ViewEvent> failed = new ArrayList<>();
        try {
            List<Object> results = stringRedisTemplate.executePipelined(
                    (RedisCallback<Object>) connection -> {
                        for (ViewEvent event : events) {
                            connection.hashCommands().hIncrBy(
                                    event.hashKey().getBytes(StandardCharsets.UTF_8),
                                    event.bizId().toString().getBytes(StandardCharsets.UTF_8),
                                    1);
                        }
                        return null;
                    });
            for (int i = 0; i < events.size(); i++) {
                Object result = i < results.size() ? results.get(i) : null;
                if (result instanceof Throwable || result == null) {
                    failed.add(events.get(i));
                }
            }
        } catch (Exception e) {
            log.error("浏览量 Pipeline 写入 Redis 失败，chunkSize={}", events.size(), e);
            failed.addAll(events);
        }
        retryOrDrop(failed);
        return failed.size();
    }

    private void retryOrDrop(List<ViewEvent> failed) {
        for (ViewEvent event : failed) {
            if (event.retryTimes() >= MAX_RETRY_TIMES) {
                droppedCount.incrementAndGet();
                log.error("浏览量事件重试 {} 次仍失败，丢弃 hashKey={}, id={}",
                        event.retryTimes(), event.hashKey(), event.bizId());
            } else if (!queue.offer(event.retry())) {
                droppedCount.incrementAndGet();
                log.warn("浏览量重试队列已满，丢弃事件 hashKey={}, id={}",
                        event.hashKey(), event.bizId());
            }
        }
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }
}
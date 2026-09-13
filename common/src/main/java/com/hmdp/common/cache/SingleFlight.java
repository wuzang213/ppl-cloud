package com.hmdp.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 进程内 single-flight：同一 key 并发回源时只让一个线程真正查库。
 * <p>
 * 使用专用有界线程池替代 ForkJoinPool.commonPool()，
 * 避免回源阻塞污染公共池影响 parallelStream 等其他任务。
 * <p>
 * 增强：增加3s查询超时，防止loader卡死造成线程永久阻塞
 */
@Slf4j
@Component
public class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
            new ConcurrentHashMap<>();

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(0);

    // 专用线程池，有界队列 + CallerRunsPolicy 降级
    private static final ExecutorService SINGLE_FLIGHT_EXECUTOR = new ThreadPoolExecutor(
            4, 16, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "single-flight-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static final long LOAD_TIMEOUT_MS = 3000;

    @SuppressWarnings("unchecked")
    public <T> T run(String key, Supplier<T> loader) {
        CompletableFuture<Object> future = flights.computeIfAbsent(
                key,
                k -> CompletableFuture.supplyAsync(() -> loader.get(), SINGLE_FLIGHT_EXECUTOR)
        );
        try {
            return (T) future.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("single-flight 查询被中断, key=" + key, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("single-flight 查询异常, key=" + key, e.getCause());
        } catch (TimeoutException e) {
            // 超时：取消任务，日志告警
            log.error("single-flight 查询超时 {}ms，key={}", LOAD_TIMEOUT_MS, key);
            future.cancel(true);
            throw new CompletionException("single-flight 查询超时, key=" + key, e);
        } finally {
            if (future.isDone()) {
                flights.remove(key, future);
            }
        }
    }
}

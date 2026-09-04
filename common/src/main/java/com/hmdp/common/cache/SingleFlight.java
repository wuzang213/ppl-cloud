package com.hmdp.common.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * 进程内 single-flight：同一 key 并发回源时只让一个线程真正查库。
 */
@Component
public class SingleFlight {

    private final ConcurrentHashMap<String, CompletableFuture<Object>> flights =
            new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T run(String key, Supplier<T> loader) {
        CompletableFuture<Object> future = flights.computeIfAbsent(key,
                k -> CompletableFuture.supplyAsync(() -> loader.get()));
        try {
            return (T) future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            if (future.isDone()) {
                flights.remove(key, future);
            }
        }
    }
}

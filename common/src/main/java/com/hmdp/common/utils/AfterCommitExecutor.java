package com.hmdp.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** afterCommit 异步化工具：TransactionSynchronization.afterCommit() 默认在事务提交后、同一请求线程同步执行，
 * 会阻塞至 Redis 删缓存 + GEO + 布隆等全部完成；本工具改为提交到专用有界线程池异步执行，请求线程立即释放，
 * 队列满时 CallerRunsPolicy 降级为同步执行（不丢操作）；registerSynchronization 保证事务回滚时不触发。 */
@Slf4j
public class AfterCommitExecutor {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            8, 16, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            r -> {
                Thread t = new Thread(r, "after-commit-" + SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /** 注册一个在事务提交后异步执行的任务；当前无活跃事务时任务立即异步执行。 */
    public static void execute(Runnable runnable) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有活跃事务，立即异步执行
            EXECUTOR.submit(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    log.error("afterCommit 异步任务执行失败（无事务）", e);
                }
            });
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                EXECUTOR.submit(() -> {
                    try {
                        runnable.run();
                    } catch (Exception e) {
                        log.error("afterCommit 异步任务执行失败", e);
                    }
                });
            }
        });
    }
}

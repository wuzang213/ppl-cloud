package com.hmdp.common.lock;

public interface ILock {

    /**
     * 尝试获取锁：timeoutSec 为锁持有超时（过期自动释放），返回 true 获取成功 / false 失败。
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
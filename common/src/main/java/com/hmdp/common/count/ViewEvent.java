package com.hmdp.common.count;

/** 浏览量异步计数事件：hashKey 为 Redis Hash key（如 view:shop:），bizId 为店铺/博客 id，retryTimes 为已重试次数。 */
public record ViewEvent(String hashKey, Long bizId, int retryTimes) {

    public ViewEvent(String hashKey, Long bizId) {
        this(hashKey, bizId, 0);
    }

    public ViewEvent retry() {
        return new ViewEvent(hashKey, bizId, retryTimes + 1);
    }
}

package com.hmdp.common.count;

/**
 * 浏览量异步计数事件。
 *
 * @param hashKey  Redis Hash key，例如 view:shop:
 * @param bizId    店铺/博客 id
 * @param retryTimes 当前已重试次数
 */
public record ViewEvent(String hashKey, Long bizId, int retryTimes) {

    public ViewEvent(String hashKey, Long bizId) {
        this(hashKey, bizId, 0);
    }

    public ViewEvent retry() {
        return new ViewEvent(hashKey, bizId, retryTimes + 1);
    }
}

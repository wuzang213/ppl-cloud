package com.hmdp.search.exception;

/** 索引同步失败。抛出它等于告诉调用方「这条消息必须重试」（监听器会投死信队列，重试任务再从失败表捞回处理）。
 * ⚠️ 与它相对的是「文档确实不存在」：那是正常语义（据此删索引），绝不能抛此异常，否则会把一篇正常删除的笔记无限重试。 */
public class SearchSyncException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SearchSyncException(String message) {
        super(message);
    }

    public SearchSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}

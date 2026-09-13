package com.hmdp.blog.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞关系同步消息：消费端只用于维护 Redis 点赞 ZSet。
 * 点赞通知不在此消息内，由 BlogServiceImpl 单独写 outbox 事件投递到通知交换机。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogLikeMessage {
    private Long blogId;
    private Long userId;
    private Boolean liked;
}
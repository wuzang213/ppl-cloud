package com.hmdp.blog.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 点赞关系同步消息：消费端只维护 Redis 点赞 ZSet；点赞通知另由 BlogServiceImpl 写 outbox 投递到通知交换机。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogLikeMessage {
    private Long blogId;
    private Long userId;
    private Boolean liked;
}
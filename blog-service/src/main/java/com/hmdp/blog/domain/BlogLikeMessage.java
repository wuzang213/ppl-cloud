package com.hmdp.blog.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogLikeMessage {
    private Long toUserId;
    private Long blogId;
    private Long userId;
    private Boolean liked;
}
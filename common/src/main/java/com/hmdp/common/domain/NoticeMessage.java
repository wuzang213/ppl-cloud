package com.hmdp.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoticeMessage {
    private Long toUserId;
    private String type;
    private String content;
    private Long relatedId;
}

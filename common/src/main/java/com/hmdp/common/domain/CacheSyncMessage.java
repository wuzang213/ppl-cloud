package com.hmdp.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheSyncMessage {
    private String type;
    private Long id;
    private Long shopId;
}

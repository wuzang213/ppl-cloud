package com.hmdp.common.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** 缓存同步 / outbox 消息体：eventType 区分同一聚合下的不同动作（SHOP_CREATED / SHOP_UPDATED / SHOP_DELETED，由 OutboxWriter 透传），
 * data 携带事件附加参数（GEO 坐标、秒杀库存等），消费端按需取用。 */
@Data
@NoArgsConstructor
public class CacheSyncMessage {
    private String type;
    private Long id;
    private Long shopId;
    /** 事件类型，供消费者区分同一聚合下的具体动作 */
    private String eventType;
    /** 事件附加参数 */
    private Map<String, Object> data;

    public CacheSyncMessage(String type, Long id) {
        this(type, id, null);
    }

    public CacheSyncMessage(String type, Long id, Long shopId) {
        this.type = type;
        this.id = id;
        this.shopId = shopId;
    }

    public CacheSyncMessage(String type, Long id, Long shopId, String eventType) {
        this.type = type;
        this.id = id;
        this.shopId = shopId;
        this.eventType = eventType;
    }
}

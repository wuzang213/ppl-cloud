package com.hmdp.shop.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.common.domain.ViewCountMessage;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 Kafka 浏览量消息，批量更新 MySQL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopViewConsumer {

    private final ShopMapper shopMapper;

    @KafkaListener(topics = "shop-view-count", groupId = "shop-view-count-group")
    public void onViewCount(ViewCountMessage message) {
        if (message == null || message.getBizId() == null || message.getCount() == null) {
            return;
        }
        shopMapper.update(null, new LambdaUpdateWrapper<Shop>()
                .eq(Shop::getId, message.getBizId())
                .setSql("view_count = view_count + " + message.getCount()));
    }
}

package com.hmdp.blog.task;

import com.hmdp.blog.service.impl.BlogViewCountService;
import com.hmdp.common.domain.ViewCountMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 消费 Kafka 博客浏览量消息，幂等地累加到 MySQL。

 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogViewConsumer {

    private final BlogViewCountService blogViewCountService;

    @KafkaListener(topics = "blog-view-count", groupId = "blog-view-count-group")
    public void onViewCount(ConsumerRecord<String, ViewCountMessage> record, Acknowledgment ack) {
        ViewCountMessage message = record.value();
        if (message == null || message.getBizId() == null || message.getCount() == null) {
            // 脏消息重试多少次都不会变好，直接 ack 跳过，避免堵住分区
            log.warn("浏览量消息内容非法，已跳过: topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            ack.acknowledge();
            return;
        }
        // 幂等登记 + view_count 累加在同一事务内完成，事务提交之后才 ack 提交 offset
        boolean applied = blogViewCountService.applyOnce(
                record.topic(), record.partition(), record.offset(),
                message.getBizId(), message.getCount());
        if (!applied) {
            log.info("浏览量消息重复投递，已幂等跳过: topic={}, partition={}, offset={}, blogId={}",
                    record.topic(), record.partition(), record.offset(), message.getBizId());
        }
        ack.acknowledge();
    }
}

package com.hmdp.blog.task;

import com.hmdp.common.count.ViewCounter;
import com.hmdp.common.domain.ViewCountMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.hmdp.common.constants.RedisConstants.VIEW_BLOG_KEY;

/**
 * 博客浏览量：定时把 Redis 聚合计数投递到 Kafka，由消费端批量刷 MySQL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogViewProducerTask {

    private final ViewCounter viewCounter;

    private final KafkaTemplate<String, ViewCountMessage> kafkaTemplate;

    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    public void publish() {
        List<Object> pairs = viewCounter.takeAndReset(VIEW_BLOG_KEY);
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            ViewCountMessage message = new ViewCountMessage(
                    "BLOG",
                    Long.valueOf(pairs.get(i).toString()),
                    Long.valueOf(pairs.get(i + 1).toString()));
            kafkaTemplate.send("blog-view-count", message);
        }
    }
}

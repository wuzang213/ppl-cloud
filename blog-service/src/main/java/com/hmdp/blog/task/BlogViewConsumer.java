package com.hmdp.blog.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.blog.domain.Blog;
import com.hmdp.blog.mapper.BlogMapper;
import com.hmdp.common.domain.ViewCountMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 Kafka 博客浏览量消息，批量更新 MySQL。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlogViewConsumer {

    private final BlogMapper blogMapper;

    @KafkaListener(topics = "blog-view-count", groupId = "blog-view-count-group")
    public void onViewCount(ViewCountMessage message) {
        if (message == null || message.getBizId() == null || message.getCount() == null) {
            return;
        }
        blogMapper.update(null, new LambdaUpdateWrapper<Blog>()
                .eq(Blog::getId, message.getBizId())
                .setSql("view_count = view_count + " + message.getCount()));
    }
}

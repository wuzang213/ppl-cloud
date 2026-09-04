package com.hmdp.blog.listener;

import com.hmdp.blog.domain.Notice;
import com.hmdp.blog.service.INoticeService;
import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.NoticeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Component
public class NoticeListener {

    @Resource
    private INoticeService noticeService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.NOTICE_QUEUE, durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")),
            exchange = @Exchange(name = MqConstants.NOTICE_DIRECT_EXCHANGE, type = "direct"),
            key = MqConstants.NOTICE_ROUTING_KEY
    ))
    public void handleNotice(NoticeMessage message) {
        if (message == null || message.getToUserId() == null) {
            log.warn("invalid notice message: {}", message);
            return;
        }
        Notice notice = new Notice();
        notice.setUserId(message.getToUserId());
        notice.setType(message.getType());
        notice.setContent(message.getContent());
        notice.setRelatedId(message.getRelatedId());
        notice.setIsRead(false);
        notice.setCreateTime(LocalDateTime.now());
        noticeService.save(notice);
    }
}

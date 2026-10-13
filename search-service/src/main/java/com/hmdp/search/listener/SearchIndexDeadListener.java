package com.hmdp.search.listener;

import com.hmdp.common.constants.MqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 死信队列消费者：只做告警输出，不落 {@code search_index_failed} 表 —— 失败记录已在 {@code SearchIndexListener} 的 catch 里写过（那才是重试依据），
 * 再写一次会让同一故障在表里躺两行、重试任务重复处理。入参用原始 {@link Message}：到达此队列的既有 {@code SearchFailEnvelope}，也有容器 reject 后由 x-dead-letter-* 转投的原始报文。 */
@Slf4j
@Component
public class SearchIndexDeadListener {

    private static final int MAX_LOG_LENGTH = 512;

    @RabbitListener(queues = MqConstants.SEARCH_INDEX_DEAD_QUEUE)
    public void onDeadLetter(Message message) {
        String body = message.getBody() == null
                ? "" : new String(message.getBody(), StandardCharsets.UTF_8);
        if (body.length() > MAX_LOG_LENGTH) {
            body = body.substring(0, MAX_LOG_LENGTH) + "...(truncated)";
        }
        log.error("【索引同步死信】需要人工确认，失败表 search_index_failed 中有对应记录，原始报文={}", body);
    }
}

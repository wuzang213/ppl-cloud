package com.hmdp.common.utils;

import cn.hutool.core.lang.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@Slf4j
@RequiredArgsConstructor
public class RabbitMqHelper {

    private final RabbitTemplate rabbitTemplate;

    public void sendMessage(String exchange, String routingKey, Object msg){
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, msg);
            log.debug("消息发送成功，exchange: {}, routingKey: {}", exchange, routingKey);
        } catch (Exception e) {
            log.error("消息发送失败，exchange: {}, routingKey: {}", exchange, routingKey, e);
            throw new RuntimeException("消息发送失败", e);
        }
    }

    public void sendDelayMessage(String exchange, String routingKey, Object msg, int delay){
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, msg,
                    message -> {
                        message.getMessageProperties().setDelay(delay);
                        return message;
                    });
            log.debug("延迟消息发送成功，exchange: {}, routingKey: {}, delay: {}ms",
                    exchange, routingKey, delay);
        } catch (Exception e) {
            log.error("延迟消息发送失败", e);
            throw new RuntimeException("延迟消息发送失败", e);
        }
    }

    public void sendMessageWithConfirm(String exchange, String routingKey, Object msg, int maxRetries) {
        log.debug("准备发送消息，exchange:{}, routingKey:{}, msg:{}", exchange, routingKey, msg);
        sendWithRetry(exchange, routingKey, msg, maxRetries, 0);
    }

    private void sendWithRetry(String exchange, String routingKey, Object msg, int maxRetries, int currentRetry) {
        CorrelationData cd = new CorrelationData(UUID.randomUUID().toString(true));
        cd.getFuture().addCallback(
                result -> {
                    // 收到 NACK（消息发送失败）
                    if (result != null && !result.isAck()) {
                        log.warn("消息发送失败，收到NACK，当前重试次数：{}/{}", currentRetry, maxRetries);

                        // 判断是否达到最大重试次数
                        if (currentRetry >= maxRetries) {
                            log.error("消息发送重试次数耗尽，发送失败，exchange:{}, routingKey:{}", exchange, routingKey);
                            // 这里可以记录失败消息到数据库或发送告警
                            return;
                        }

                        // 递归重试（只有未达到最大次数才执行）
                        sendWithRetry(exchange, routingKey, msg, maxRetries, currentRetry + 1);
                    } else if (result != null && result.isAck()) {
                        // 收到 ACK（消息发送成功）
                        log.debug("消息发送成功，exchange:{}, routingKey:{}", exchange, routingKey);
                    }
                },
                ex -> {
                    // 发生异常
                    log.error("处理ACK回执异常，exchange:{}, routingKey:{}", exchange, routingKey, ex);

                    // 只有未达到最大次数才重试
                    if (currentRetry < maxRetries) {
                        log.debug("异常后准备第 {} 次重试", currentRetry + 1);
                        sendWithRetry(exchange, routingKey, msg, maxRetries, currentRetry + 1);
                    } else {
                        log.error("重试次数耗尽，消息发送最终失败");
                    }
                }
        );

        rabbitTemplate.convertAndSend(exchange, routingKey, msg, cd);
    }
}
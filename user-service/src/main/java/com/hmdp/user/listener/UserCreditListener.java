package com.hmdp.user.listener;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.common.domain.OrderPaidMessage;
import com.hmdp.user.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 用户积分监听器
 */
@Slf4j
@Component
public class UserCreditListener {

    @Resource
    private IUserService userService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.USER_CREDIT_QUEUE, durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")),
            exchange = @Exchange(name = MqConstants.SECKILL_DIRECT_EXCHANGE, type = "direct"),
            key = MqConstants.ORDER_PAID_ROUTING_KEY
    ))
    public void handleOrderPaid(OrderPaidMessage message) {
        if (message == null || message.getUserId() == null) {
            log.warn("invalid order paid message: {}", message);
            return;
        }
        userService.addCreditsByOrder(message.getUserId());
    }
}

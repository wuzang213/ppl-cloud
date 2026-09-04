package com.hmdp.voucher.listener;

import com.hmdp.common.constants.MqConstants;
import com.hmdp.voucher.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Argument;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class OrderTimeoutListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.ORDER_TIMEOUT_QUEUE, durable = "true",
                    arguments = @Argument(name = "x-queue-mode", value = "lazy")),
            exchange = @Exchange(name = MqConstants.SECKILL_DIRECT_EXCHANGE, type = "direct"),
            key = MqConstants.ORDER_TIMEOUT_ROUTING_KEY
    ))
    public void handleTimeout(Long orderId) {
        log.info("处理订单超时关闭，orderId={}", orderId);
        voucherOrderService.closeTimeoutOrder(orderId);
    }
}

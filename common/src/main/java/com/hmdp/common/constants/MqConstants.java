package com.hmdp.common.constants;

/**
 * RabbitMQ 相关常量
 */
public interface MqConstants {

    // ========== 秒杀下单 Direct 队列 ==========
    String SECKILL_DIRECT_EXCHANGE = "seckill.direct";
    String SECKILL_ORDER_QUEUE = "seckill.order.queue";
    String SECKILL_ORDER_ROUTING_KEY = "seckill.order";

    // 秒杀订单死信交换机 + 死信队列（重试耗尽后进入死信，补偿 Redis 库存）
    String SECKILL_DLX_EXCHANGE = "seckill.dlx";
    String SECKILL_ORDER_DEAD_QUEUE = "seckill.order.dead.queue";
    String SECKILL_ORDER_DEAD_ROUTING_KEY = "seckill.order.dead";

    // ========== 博客点赞 Direct 队列 ==========
    String BLOG_LIKE_DIRECT_EXCHANGE = "blog.like.direct";
    String BLOG_LIKE_QUEUE = "blog.like.queue";
    String BLOG_LIKE_ROUTING_KEY = "blog.like";

    // ========== 消息通知 Direct 队列 ==========
    String NOTICE_DIRECT_EXCHANGE = "notice.direct";
    String NOTICE_QUEUE = "notice.queue";
    String NOTICE_ROUTING_KEY = "notice";

    // ========== 订单超时 Direct 队列 ==========
    String ORDER_TIMEOUT_QUEUE = "order.timeout.queue";
    String ORDER_TIMEOUT_ROUTING_KEY = "order.timeout";

    // ========== 订单支付积分 ==========
    String ORDER_PAID_ROUTING_KEY = "order.paid";
    String USER_CREDIT_QUEUE = "user.credit.queue";

    int MQ_RETRY_TIMES = 3;

}
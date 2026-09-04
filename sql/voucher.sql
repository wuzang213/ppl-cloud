create table business_outbox
(
    id              bigint auto_increment
        primary key,
    aggregate_type  varchar(32)                        not null,
    aggregate_id    bigint                             not null,
    event_type      varchar(64)                        not null,
    payload         text                               not null,
    status          tinyint  default 0                 not null,
    retry_count     int      default 0                 not null,
    next_retry_time datetime default CURRENT_TIMESTAMP not null,
    created_time    datetime default CURRENT_TIMESTAMP not null,
    updated_time    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP
)
    charset = utf8mb4;

create index idx_status_time
    on business_outbox (status, next_retry_time);

create table outbox_consume_record
(
    message_id   varchar(64)                        not null
        primary key,
    created_time datetime default CURRENT_TIMESTAMP not null
)
    charset = utf8mb4;

create table tb_seckill_voucher
(
    voucher_id  bigint unsigned                     not null comment '关联的优惠券的id'
        primary key,
    stock       int                                 not null comment '库存',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    begin_time  timestamp default CURRENT_TIMESTAMP not null comment '生效时间',
    end_time    timestamp default CURRENT_TIMESTAMP not null comment '失效时间',
    update_time timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    comment '秒杀优惠券表，与优惠券是一对一关系' row_format = COMPACT;

create table tb_voucher
(
    id           bigint unsigned auto_increment comment '主键'
        primary key,
    shop_id      bigint unsigned                            null comment '商铺id',
    title        varchar(255)                               not null comment '代金券标题',
    sub_title    varchar(255)                               null comment '副标题',
    rules        varchar(1024)                              null comment '使用规则',
    pay_value    bigint unsigned                            not null comment '支付金额，单位是分。例如200代表2元',
    actual_value bigint                                     not null comment '抵扣金额，单位是分。例如200代表2元',
    type         tinyint unsigned default '0'               not null comment '0,普通券；1,秒杀券',
    status       tinyint unsigned default '1'               not null comment '1,上架; 2,下架; 3,过期',
    create_time  timestamp        default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  timestamp        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    row_format = COMPACT;

create table tb_voucher_order
(
    id          bigint                                     not null comment '主键'
        primary key,
    user_id     bigint unsigned                            not null comment '下单的用户id',
    voucher_id  bigint unsigned                            not null comment '购买的代金券id',
    pay_type    tinyint unsigned default '1'               not null comment '支付方式 1：余额支付；2：支付宝；3：微信',
    status      tinyint unsigned default '1'               not null comment '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
    create_time timestamp        default CURRENT_TIMESTAMP not null comment '下单时间',
    pay_time    timestamp                                  null comment '支付时间',
    use_time    timestamp                                  null comment '核销时间',
    refund_time timestamp                                  null comment '退款时间',
    update_time timestamp        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    row_format = COMPACT;



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

create table tb_shop
(
    id          bigint unsigned auto_increment comment '主键'
        primary key,
    name        varchar(128)                        not null comment '商铺名称',
    type_id     bigint unsigned                     not null comment '商铺类型的id',
    images      varchar(1024)                       not null comment '商铺图片，多个图片以'',''隔开',
    area        varchar(128)                        null comment '商圈，例如陆家嘴',
    address     varchar(255)                        not null comment '地址',
    x           double unsigned                     not null comment '经度',
    y           double unsigned                     not null comment '维度',
    avg_price   bigint unsigned                     null comment '均价，取整数',
    sold        int unsigned zerofill               not null comment '销量',
    comments    int unsigned zerofill               not null comment '评论数量',
    score       int(2) unsigned zerofill            not null comment '评分，1~5分，乘10保存，避免小数',
    open_hours  varchar(32)                         null comment '营业时间，例如 10:00-22:00',
    create_time timestamp default CURRENT_TIMESTAMP null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间',
    view_count  bigint    default 0                 not null
)
    row_format = COMPACT;

create index foreign_key_type
    on tb_shop (type_id);

create table tb_shop_favorite
(
    id          bigint auto_increment comment '主键'
        primary key,
    user_id     bigint                              not null comment '用户id',
    shop_id     bigint                              not null comment '店铺id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    constraint uk_user_shop
        unique (user_id, shop_id)
)
    comment '店铺收藏表' charset = utf8mb4;

create table tb_shop_type
(
    id          bigint unsigned auto_increment comment '主键'
        primary key,
    name        varchar(32)                         null comment '类型名称',
    icon        varchar(255)                        null comment '图标',
    sort        int unsigned                        null comment '顺序',
    create_time timestamp default CURRENT_TIMESTAMP null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP comment '更新时间'
)
    row_format = COMPACT;



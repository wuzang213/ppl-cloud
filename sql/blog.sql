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

create table tb_blog
(
    id          bigint unsigned auto_increment comment '主键'
        primary key,
    shop_id     bigint                                   not null comment '商户id',
    user_id     bigint unsigned                          not null comment '用户id',
    title       varchar(255) collate utf8mb4_unicode_ci  not null comment '标题',
    images      varchar(2048) default ''                 null,
    content     varchar(2048) collate utf8mb4_unicode_ci not null comment '探店的文字描述',
    liked       int unsigned  default '0'                null comment '点赞数量',
    comments    int unsigned                             null comment '评论数量',
    create_time timestamp     default CURRENT_TIMESTAMP  not null comment '创建时间',
    update_time timestamp     default CURRENT_TIMESTAMP  not null on update CURRENT_TIMESTAMP comment '更新时间',
    view_count  bigint        default 0                  not null
)
    row_format = COMPACT;

create table tb_blog_comments
(
    id          bigint unsigned auto_increment comment '主键'
        primary key,
    user_id     bigint unsigned                     not null comment '用户id',
    blog_id     bigint unsigned                     not null comment '探店id',
    parent_id   bigint unsigned                     not null comment '关联的1级评论id，如果是一级评论，则值为0',
    answer_id   bigint unsigned                     not null comment '回复的评论id',
    content     varchar(255)                        not null comment '回复的内容',
    liked       int unsigned                        null comment '点赞数',
    status      tinyint unsigned                    null comment '状态，0：正常，1：被举报，2：禁止查看',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    row_format = COMPACT;

create table tb_blog_favorite
(
    id          bigint auto_increment comment '主键'
        primary key,
    user_id     bigint                              not null comment '用户id',
    blog_id     bigint                              not null comment '博客id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    constraint uk_user_blog
        unique (user_id, blog_id)
)
    comment '博客收藏表' charset = utf8mb4;

create table tb_follow
(
    id             bigint auto_increment comment '主键'
        primary key,
    user_id        bigint unsigned                     not null comment '用户id',
    follow_user_id bigint unsigned                     not null comment '关联的用户id',
    create_time    timestamp default CURRENT_TIMESTAMP not null comment '创建时间'
)
    row_format = COMPACT;

create table tb_notification
(
    id          bigint auto_increment comment '主键'
        primary key,
    user_id     bigint                               not null comment '接收用户id',
    type        varchar(20)                          not null comment '通知类型：LIKE/COMMENT/FOLLOW',
    content     varchar(255)                         not null comment '通知内容',
    related_id  bigint                               null comment '关联业务id',
    is_read     tinyint(1) default 0                 not null comment '是否已读：0未读 1已读',
    create_time timestamp  default CURRENT_TIMESTAMP not null comment '创建时间'
)
    comment '消息通知表' charset = utf8mb4;

create index idx_user_id
    on tb_notification (user_id);

create table undo_log
(
    branch_id     bigint       not null comment 'branch transaction id',
    xid           varchar(128) not null comment 'global transaction id',
    context       varchar(128) not null comment 'undo_log context,such as serialization',
    rollback_info longblob     not null comment 'rollback info',
    log_status    int          not null comment '0:normal status,1:defense status',
    log_created   datetime(6)  not null comment 'create datetime',
    log_modified  datetime(6)  not null comment 'modify datetime',
    constraint ux_undo_log
        unique (xid, branch_id)
)
    comment 'AT transaction mode undo table' charset = utf8mb4;



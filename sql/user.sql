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

create table tb_sign
(
    id        bigint unsigned auto_increment comment '主键'
        primary key,
    user_id   bigint unsigned  not null comment '用户id',
    year      year             not null comment '签到的年',
    month     tinyint          not null comment '签到的月',
    date      date             not null comment '签到的日期',
    is_backup tinyint unsigned null comment '是否补签'
)
    row_format = COMPACT;

create table tb_user
(
    id            bigint unsigned auto_increment comment '主键'
        primary key,
    phone         varchar(11)                            not null comment '手机号码',
    password      varchar(128) default ''                null comment '密码，加密存储',
    nick_name     varchar(32)  default ''                null comment '昵称，默认是用户id',
    icon          varchar(255) default ''                null comment '人物头像',
    create_time   timestamp    default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   timestamp    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    role          varchar(20)  default 'USER'            not null comment '角色：USER/ADMIN',
    token_version int          default 0                 not null,
    constraint uniqe_key_phone
        unique (phone)
)
    row_format = COMPACT;

create table tb_user_info
(
    user_id     bigint unsigned                            not null comment '主键，用户id'
        primary key,
    city        varchar(64)      default ''                null comment '城市名称',
    introduce   varchar(128)                               null comment '个人介绍，不要超过128个字符',
    fans        int unsigned     default '0'               null comment '粉丝数量',
    followee    int unsigned     default '0'               null comment '关注的人的数量',
    gender      tinyint unsigned default '0'               null comment '性别，0：男，1：女',
    birthday    date                                       null comment '生日',
    credits     int unsigned     default '0'               null comment '积分',
    level       tinyint unsigned default '0'               null comment '会员级别，0~9级,0代表未开通会员',
    create_time timestamp        default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp        default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    row_format = COMPACT;



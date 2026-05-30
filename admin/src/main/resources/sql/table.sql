SET NAMES utf8mb4;

create table admin_permission
(
    id          bigint auto_increment comment 'ID'
        primary key,
    user_id     bigint               not null comment '用户ID',
    username    varchar(256)         not null comment '用户名',
    is_admin    tinyint(1) default 0 null comment '是否管理员 0：普通用户 1：管理员',
    create_time datetime             null comment '创建时间',
    update_time datetime             null comment '修改时间',
    del_flag    tinyint(1) default 0 null comment '删除标识 0：未删除 1：已删除',
    constraint idx_unique_user_id
        unique (user_id)
)
    comment '管理员权限表';

create index idx_is_admin
    on admin_permission (is_admin);

create index idx_username
    on admin_permission (username);

create table t_user
(
    id            bigint auto_increment comment 'ID'
        primary key,
    username      varchar(256) null comment '用户名',
    password      varchar(512) null comment '密码',
    real_name     varchar(256) null comment '真实姓名',
    phone         varchar(128) null comment '手机号',
    mail          varchar(512) null comment '邮箱',
    deletion_time bigint       null comment '注销时间戳',
    create_time   datetime     null comment '创建时间',
    update_time   datetime     null comment '修改时间',
    del_flag      tinyint(1)   null comment '删除标识 0：未删除 1：已删除',
    constraint idx_unique_username
        unique (username)
);

package com.hmdp.common.search;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 文章搜索文档（blog-service 侧的「反查出口」，GET /blog/search-doc/** 响应体，search-service 经 Feign 转 ES 文档）；
 * 不直接返回 Blog 实体：Blog.name/icon 是 @TableField(exist = false)、作者名要 Feign 调 user-service，
 * 列表场景逐条补作者会把跨服务开销搬进搜索链路，故写入索引时就把作者冗余进文档，读取侧零跨服务调用。 */
@Data
public class BlogSearchDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long shopId;

    private Long userId;

    private String title;

    /** 逗号分隔的图片地址原串（与 {@code GET /blog/{id}} 返回的字段形态一致）；ES 侧是 keyword、只存不索引 */
    private String images;

    private String content;

    private Integer liked;

    private Integer comments;

    private Long viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 冗余字段：作者昵称（来自 user-service，tb_blog 里没有） */
    private String authorName;

    /** 冗余字段：作者头像（来自 user-service，tb_blog 里没有） */
    private String authorIcon;
}

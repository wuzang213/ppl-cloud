package com.hmdp.search.doc;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** {@code hmdp_blog} 索引的文档结构。{@code _id = tb_blog.id}，ES 同 id 覆盖所以 upsert 天然幂等（增量同步只靠消息驱动的地基）。
 * {@code authorName/authorIcon} 是唯一需主动冗余的字段（不在 {@code tb_blog} 表里，由 blog-service 写入侧调 user-service 补齐）；⚠️ 刻意不冗余 {@code shopName} 也不带 {@code location}（文章卡片不展示，且店铺改名失效半径最大）。 */
@Data
public class BlogDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /** 由 content 清洗截断而来（tb_blog 没有 summary 列） */
    private String summary;

    private String content;

    private Long authorId;

    private String authorName;

    private String authorIcon;

    private Long shopId;

    /** 原始图片串，只存不索引（与 GET /blog/{id} 返回的字段形态一致） */
    private String images;

    private Integer liked;

    private Integer comments;

    private Long viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

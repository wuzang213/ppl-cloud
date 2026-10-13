package com.hmdp.search.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 文章搜索命中的一条。字段全部来自 ES 文档本身，查询时不再调 blog-service / user-service ——
 * 写入侧把作者冗余进了索引（见 {@code BlogSearchDocDTO}），换来读取路径零跨服务调用。 */
@Data
public class SearchHitVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    /**
     * 标题的 HTML 安全片段（命中词包在 {@code <em>} 里、其余已转义，无命中时等于转义后的 {@code title}）；⚠️ 可放心 {@code v-html}，但 {@code title}/{@code authorName} 等原始字段是纯文本，须插值渲染。
     */
    private String titleHighlight;

    /**
     * 正文片段：命中高亮（HTML 安全片段），无高亮时退化为转义后的 {@code summary}。
     */
    private String snippet;

    private Long authorId;

    private String authorName;

    private String authorIcon;

    /** 仅用于跳转店铺详情 / 按店筛选，不展示店名（shopName 已决策不冗余） */
    private Long shopId;

    private Integer liked;

    private LocalDateTime createTime;

    /** 相关度，调试用，前端可忽略 */
    private Float score;
}

package com.hmdp.search.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 店铺搜索命中的一条 */
@Data
public class ShopHitVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    /** 店名的 HTML 安全片段（命中词包在 {@code <em>} 里、其余已 HTML 转义，无命中时等于转义后的 {@code name}）；前端可放心 {@code v-html}。
     * 而 {@code name} / {@code address} 等原始字段是纯文本，请用插值渲染。 */
    private String nameHighlight;

    private Long typeId;

    /** 分类名。搜品类词（如"火锅"）时命中的就是它，卡片上需要展示 */
    private String typeName;

    private String area;

    private String address;

    private String images;

    private Long avgPrice;

    private Integer score;

    private Integer sold;

    private Integer comments;

    private Long viewCount;

    private String openHours;

    private LocalDateTime createTime;

    private Float docScore;
}

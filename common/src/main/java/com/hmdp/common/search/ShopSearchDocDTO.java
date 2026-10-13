package com.hmdp.common.search;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 店铺搜索文档（shop-service 侧的「反查出口」）：typeId/typeName 来自 tb_shop_type，把分类名作为可检索字段冗余，
 * 以支撑「搜品类词出店」（店名多不含品类词，如「蜀大侠」要能搜「火锅」命中），分类另有 terms 聚合供筛选器使用；
 * ⚠️ 只有店铺索引冗余类型名，文章索引不冗余 shopName（文章卡片不展示店名，且店铺改名失效半径最大）。见 站内搜索-ES同步方案.md §4.3 ⑥。 */
@Data
public class ShopSearchDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private Long typeId;

    /** 冗余字段：分类名，来自 tb_shop_type，参与检索 */
    private String typeName;

    private String images;

    private String area;

    private String address;

    private Double x;

    private Double y;

    private Long avgPrice;

    private Integer sold;

    private Integer comments;

    private Long viewCount;

    private Integer score;

    private String openHours;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

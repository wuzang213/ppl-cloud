package com.hmdp.search.doc;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/** {@code hmdp_shop} 索引的文档结构。{@code typeName} 是这里唯一有实际检索价值的冗余字段：
 * 店名往往不含品类词（如「蜀大侠」），纳入检索字段后搜「火锅」才能捞出这些店 —— 这也是"分类不独立建索引、直接融进店铺文档"的决策价值。 */
@Data
public class ShopDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private Long typeId;

    /** 冗余自 tb_shop_type，参与检索（multi_match 的 typeName 字段）与 terms 聚合 */
    private String typeName;

    private String area;

    private String address;

    /** geo_point，形如 {@code {"lat": y, "lon": x}}。
     * ⚠️ 项目里 x = 经度、y = 纬度（见 {@code ShopServiceImpl.queryShopByType} 的 {@code GeoReference.fromCoordinate(x, y)}），别写反。 */
    private Map<String, Double> location;

    /** 原始图片串，只存不索引 */
    private String images;

    private Long avgPrice;

    private Integer score;

    private Integer sold;

    private Integer comments;

    private Long viewCount;

    private String openHours;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

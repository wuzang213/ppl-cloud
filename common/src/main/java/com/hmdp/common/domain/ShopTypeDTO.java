package com.hmdp.common.domain;

import lombok.Data;

import java.io.Serializable;

/** 店铺分类跨模块传输对象（供 search-service 把分类改名广播翻译成新分类名）：字段与 tb_shop_type 序列化形态一致
 * （createTime/updateTime 实体侧 @JsonIgnore 不传输），由 GET /shop-type/list 产出、ShopClient 消费。 */
@Data
public class ShopTypeDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private String icon;

    private Integer sort;
}

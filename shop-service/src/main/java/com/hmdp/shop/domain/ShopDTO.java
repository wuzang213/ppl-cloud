package com.hmdp.shop.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

// DTO，用于接收新增/修改，不要时间字段
@Schema(description = "店铺新增/修改表单")
@Data
public class ShopDTO {
    private Long id;
    private String name;
    private Long typeId;
    private String images;
    private String area;
    private String address;
    private Double x;
    private Double y;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;
    private Integer score;
    private String openHours;
}
package com.hmdp.shop.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import org.springframework.data.annotation.Transient;
import top.javatool.canal.client.annotation.CanalTable;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_shop")
@CanalTable("tb_shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
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

    private Long viewCount;

    private Integer score;

    private String openHours;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


    @TableField(exist = false)
    @Transient
    private Double distance;
}

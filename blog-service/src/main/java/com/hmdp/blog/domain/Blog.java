package com.hmdp.blog.domain;

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
@TableName("tb_blog")
@CanalTable("tb_blog")
public class Blog implements Serializable {

    private static final long serialVersionUID = 1L;
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long shopId;

    private Long userId;

    @TableField(exist = false)
    @Transient
    private String icon;

    @TableField(exist = false)
    @Transient
    private String name;

    @TableField(exist = false)
    @Transient
    private Boolean isLike;

    private String title;

    private String images;

    private String content;

    private Integer liked;

    private Integer comments;

    private Long viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;


}

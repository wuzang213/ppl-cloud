package com.hmdp.blog.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.annotation.Transient;

@Schema(description = "探店笔记表单")
@Data
public class BlogDTO {
    private Long id;

    private Long shopId;

    private Long userId;

    private String icon;

    private String name;

    private Boolean isLike;

    private String title;

    private String images;

    private String content;

    private Integer liked;

    private Integer comments;
}

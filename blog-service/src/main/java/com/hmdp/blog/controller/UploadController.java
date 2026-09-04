package com.hmdp.blog.controller;

import com.hmdp.blog.service.UploadService;
import com.hmdp.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;

@Tag(name = "文件上传服务", description = "博客图片上传与删除")
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private UploadService uploadService;

    @Operation(summary = "上传博客图片")
    @PostMapping("/blog")
    public Result uploadImage(@RequestParam("file") @Parameter(description = "图片文件") MultipartFile image) throws IOException {
        return uploadService.uploadImage(image);
    }

    @Operation(summary = "删除博客图片")
    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") @Parameter(description = "文件名") String filename) {
        return uploadService.deleteBlogImg(filename);
    }
}
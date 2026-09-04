package com.hmdp.blog.controller;

import com.hmdp.blog.service.INoticeService;
import com.hmdp.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "消息通知服务", description = "消息列表、已读")
@RestController
@RequestMapping("/blog/notice")
public class NoticeController {

    @Resource
    private INoticeService noticeService;

    @Operation(summary = "查询我的消息通知")
    @GetMapping("/list")
    public Result list(@RequestParam(value = "current", defaultValue = "1") @Parameter(description = "页码") Integer current) {
        return noticeService.listByUser(current);
    }

    @Operation(summary = "单条消息标记已读")
    @PutMapping("/read/{id}")
    public Result read(@PathVariable("id") @Parameter(description = "消息ID") Long id) {
        return noticeService.markRead(id);
    }

    @Operation(summary = "全部消息标记已读")
    @PutMapping("/read/all")
    public Result readAll() {
        return noticeService.markAllRead();
    }
}
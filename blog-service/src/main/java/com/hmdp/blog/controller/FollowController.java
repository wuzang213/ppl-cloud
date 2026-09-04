package com.hmdp.blog.controller;

import com.hmdp.blog.service.IFollowService;
import com.hmdp.common.domain.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@Tag(name = "关注服务", description = "关注、取消关注、共同关注")
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    @Operation(summary = "关注或取消关注")
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") @Parameter(description = "被关注用户ID") Long followUserId,
                         @PathVariable("isFollow") @Parameter(description = "是否关注") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @Operation(summary = "查询是否已关注")
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") @Parameter(description = "被关注用户ID") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @Operation(summary = "查询共同关注")
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") @Parameter(description = "用户ID") Long id) {
        return followService.followCommons(id);
    }
}
package com.hmdp.user.controller;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.domain.Result;
import com.hmdp.user.domain.LoginFormDTO;
import com.hmdp.user.domain.UserInfoDTO;
import com.hmdp.user.service.IUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.List;

@Slf4j
@Tag(name = "用户服务", description = "登录、注册、签到、用户信息")
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Operation(summary = "发送短信验证码")
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") @Parameter(description = "手机号") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    @Operation(summary = "登录，返回 accessToken/refreshToken")
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        return userService.login(loginForm, session);
    }

    @Operation(summary = "刷新 accessToken")
    @PostMapping("/refresh")
    public Result refresh(@RequestParam("refreshToken") @Parameter(description = "刷新令牌") String refreshToken) {
        return userService.refreshToken(refreshToken);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result logout(@RequestParam("refreshToken") @Parameter(description = "刷新令牌") String refreshToken) {
        return userService.logout(refreshToken);
    }

    @Operation(summary = "当前用户全设备下线")
    @PostMapping("/kick-all")
    @LoginRequired
    public Result kickAll() {
        return userService.kickAllDevices();
    }

    @Operation(summary = "查询当前登录用户")
    @GetMapping("/me")
    @LoginRequired
    public Result me() {
        return userService.getMe();
    }

    @Operation(summary = "查询用户详细信息")
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") @Parameter(description = "用户ID") Long userId) {
        return userService.queryUserInfo(userId);
    }

    @Operation(summary = "查询用户基本信息")
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") @Parameter(description = "用户ID") Long userId) {
        return userService.queryUserById(userId);
    }

    @Operation(summary = "签到")
    @PostMapping("/sign")
    @LoginRequired
    public Result sign() {
        return userService.sign();
    }

    @Operation(summary = "查询签到次数")
    @GetMapping("/sign/count")
    @LoginRequired
    public Result signCount() {
        return userService.signCount();
    }

    @Operation(summary = "批量查询用户")
    @PostMapping("/list")
    public Result listUserByIds(@RequestBody List<Long> userIdList) {
        return userService.listUserByIds(userIdList);
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result register(@RequestBody LoginFormDTO loginForm) {
        return userService.register(loginForm);
    }

    @Operation(summary = "修改用户信息")
    @PutMapping("/info/update")
    @LoginRequired
    public Result updateInfo(@RequestBody UserInfoDTO dto) {
        return userService.updateInfo(dto);
    }

    @Operation(summary = "查询积分与等级")
    @GetMapping("/credits")
    @LoginRequired
    public Result credits() {
        return userService.getCredits();
    }
}
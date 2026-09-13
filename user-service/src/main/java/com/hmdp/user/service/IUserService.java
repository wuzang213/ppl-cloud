package com.hmdp.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.user.domain.LoginFormDTO;
import com.hmdp.user.domain.UserInfoDTO;
import com.hmdp.user.domain.User;

import javax.servlet.http.HttpSession;
import java.util.List;

public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
    Result logout(String refreshToken);
    Result refreshToken(String refreshToken);
    Result kickAllDevices();

    Result register(LoginFormDTO loginForm);

    Result updateInfo(UserInfoDTO info);

    Result getCredits();

    void addCreditsByOrder(Long userId);

    /**
     * 签到积分补偿：由 UserCacheListener 消费 outbox 事件时调用。
     */
    void addSignCredits(Long userId);

    Result getMe();

    Result queryUserInfo(Long userId);

    Result queryUserById(Long userId);

    Result listUserByIds(List<Long> userIdList);

    Result sign();

    Result signCount();
}

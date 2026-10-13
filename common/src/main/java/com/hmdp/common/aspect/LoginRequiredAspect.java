package com.hmdp.common.aspect;

import com.hmdp.common.annotation.LoginRequired;
import com.hmdp.common.exception.UnauthorizedException;
import com.hmdp.common.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/** @LoginRequired 的 AOP 切面：方法执行前校验 UserHolder.getUser()，
 * 为 null 说明未携带有效 user-info header，抛 UnauthorizedException(401) 交 GlobalExceptionHandler。 */
@Slf4j
@Aspect
@Component
public class LoginRequiredAspect {

    @Before("@annotation(loginRequired)")
    public void checkLogin(LoginRequired loginRequired) {
        if (UserHolder.getUser() == null) {
            throw new UnauthorizedException("未登录");
        }
    }
}

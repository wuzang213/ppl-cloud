package com.hmdp.common.interceptors;

import cn.hutool.core.util.StrUtil;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 修复：拦截器只负责"解析 header → 存 UserHolder"，不做鉴权。
 * 鉴权交给 @LoginRequired 注解 + LoginRequiredAspect AOP 切面。
 * 不再从 header 读 nickName/icon（JWT 已瘦身，网关不再透传）。
 */
@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader("user-info");
        if (StrUtil.isNotBlank(userId)) {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(Long.valueOf(userId));
            userDTO.setRole(request.getHeader("user-role"));
            UserHolder.saveUser(userDTO);
        }
        // 永远放行：鉴权由 AOP 切面在需要登录的方法上执行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
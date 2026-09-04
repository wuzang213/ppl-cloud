package com.hmdp.common.interceptors;

import cn.hutool.core.util.StrUtil;
import com.hmdp.common.domain.UserDTO;
import com.hmdp.common.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class UserInfoInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        log.info("=== UserInfoInterceptor.preHandle 执行 ===");
        String userId = request.getHeader("user-info");
        log.info("=== user-info header: " + userId);
        if (StrUtil.isNotBlank(userId)) {
            UserDTO userDTO = new UserDTO();
            userDTO.setId(Long.valueOf(userId));
            userDTO.setNickName(request.getHeader("user-nickname"));
            userDTO.setIcon(request.getHeader("user-icon"));
            userDTO.setRole(request.getHeader("user-role"));
            UserHolder.saveUser(userDTO);
        }else {
            log.info("=== 用户信息为空 ===");
            /**
            // 模拟全局异常处理器返回结果，和抛出UnauthorizedException输出完全一样
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");

            Map<String, Object> result = new HashMap<>();
            result.put("code", 401);
            result.put("message", "未登录，请先登录");
            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false; //终止请求，不再走controller
             **/
            // 没有用户头直接放过，交给controller执行业务
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
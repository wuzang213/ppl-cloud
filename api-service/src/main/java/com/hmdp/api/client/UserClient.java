package com.hmdp.api.client;

import com.hmdp.api.client.fallback.UserClientFallback;
import com.hmdp.api.config.DefaultFeignConfig;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

@FeignClient(value = "user-service",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = UserClientFallback.class)
public interface UserClient {

    //批量查询用户
    @PostMapping("/user/list")
    Result<List<UserDTO>> listUserByIds(@RequestBody List<Long> userIdList);

    //单个查询用户
    @GetMapping("/user/{id}")
    Result<UserDTO> queryUserById(@PathVariable("id") Long id);
}
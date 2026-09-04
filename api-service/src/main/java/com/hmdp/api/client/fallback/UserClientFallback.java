package com.hmdp.api.client.fallback;

import cn.hutool.core.collection.CollectionUtil;
import com.hmdp.api.client.UserClient;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;


import java.util.Collections;
import java.util.List;

/**
 * 当调用userclient失败时，会调用此方法
 */
@Slf4j
public class UserClientFallback implements FallbackFactory<UserClient> {
    @Override
    public UserClient create(Throwable cause) {
        return new UserClient() {
            @Override
            public Result<List<UserDTO>> listUserByIds(List<Long> userIdList) {
                log.error("远程调用UserClient#listUserByIds方法出现异常，参数：{}", userIdList, cause);
                // 查询购物车允许失败，查询失败，返回空集合
                return Result.ok(Collections.emptyList());
            }

            @Override
            public Result<UserDTO> queryUserById(Long id) {
                log.error("远程调用queryUserById方法出现异常，参数：{}", id, cause);
                return Result.fail("查询用户失败");
            }
        };
    }
}
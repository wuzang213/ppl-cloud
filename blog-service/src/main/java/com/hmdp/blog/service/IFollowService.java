package com.hmdp.blog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.blog.domain.Follow;
import com.hmdp.common.domain.Result;


public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserId, Boolean isFollow);

    Result isFollow(Long followUserId);

    Result followCommons(Long id);
}

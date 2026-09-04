package com.hmdp.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.user.domain.UserInfo;
import com.hmdp.user.mapper.UserInfoMapper;
import com.hmdp.user.service.IUserInfoService;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}

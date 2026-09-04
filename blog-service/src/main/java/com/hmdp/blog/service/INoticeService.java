package com.hmdp.blog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.blog.domain.Notice;
import com.hmdp.common.domain.Result;

public interface INoticeService extends IService<Notice> {

    Result listByUser(Integer current);

    Result markRead(Long id);

    Result markAllRead();
}

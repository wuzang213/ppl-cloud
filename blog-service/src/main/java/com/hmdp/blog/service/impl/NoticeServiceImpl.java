package com.hmdp.blog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.blog.domain.Notice;
import com.hmdp.blog.mapper.NoticeMapper;
import com.hmdp.blog.service.INoticeService;
import com.hmdp.common.constants.SystemConstants;
import com.hmdp.common.domain.Result;
import com.hmdp.common.utils.UserHolder;
import org.springframework.stereotype.Service;

@Service
public class NoticeServiceImpl extends ServiceImpl<NoticeMapper, Notice> implements INoticeService {

    @Override
    public Result listByUser(Integer current) {
        Long userId = UserHolder.getUser().getId();
        Page<Notice> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }

    @Override
    public Result markRead(Long id) {
        Long userId = UserHolder.getUser().getId();
        update().eq("id", id).eq("user_id", userId).set("is_read", true).update();
        return Result.ok();
    }

    @Override
    public Result markAllRead() {
        Long userId = UserHolder.getUser().getId();
        update().eq("user_id", userId).set("is_read", true).update();
        return Result.ok();
    }
}

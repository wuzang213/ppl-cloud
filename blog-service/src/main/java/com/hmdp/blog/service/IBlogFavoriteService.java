package com.hmdp.blog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.blog.domain.BlogFavorite;
import com.hmdp.common.domain.Result;

public interface IBlogFavoriteService extends IService<BlogFavorite> {

    Result favorite(Long blogId, Boolean favorite);

    Result myFavorites(Integer current);

    Result isFavorite(Long blogId);
}

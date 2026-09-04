package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.ShopFavorite;

public interface IShopFavoriteService extends IService<ShopFavorite> {

    Result favorite(Long shopId, Boolean favorite);

    Result myFavorites(Integer current);

    Result isFavorite(Long shopId);
}

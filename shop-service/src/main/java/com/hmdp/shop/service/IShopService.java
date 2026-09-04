package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopDTO;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(ShopDTO dto);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y, Integer distance);

    Result saveShop(ShopDTO dto);

    Result queryShopByName(String name, Integer current);
}

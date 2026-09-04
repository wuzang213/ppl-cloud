package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.ShopType;


public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();
}

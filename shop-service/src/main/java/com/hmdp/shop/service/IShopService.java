package com.hmdp.shop.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.common.domain.Result;
import com.hmdp.shop.domain.Shop;
import com.hmdp.shop.domain.ShopDTO;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    /** 搜索索引专用：反查单个店铺的搜索文档（不计数、含分类名）。两处必须有别于 {@code GET /shop/{id}}：① 不调
     * {@code viewCounter.asyncIncr}，否则索引同步虚增浏览量；② 不走布隆（新店可能还没进过滤器，会被误判「不存在」
     * 而误删刚建好的文档），反查以 DB 真值为准。@return {@code Result.ok(null)} = 店铺确实不存在 */
    Result queryShopSearchDoc(Long id);

    /**
     * 搜索索引专用：全量初始化分页口子（含分类名）。
     */
    Result queryShopSearchDocPage(Integer current, Integer size);

    Result update(ShopDTO dto);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y, Integer distance);

    Result saveShop(ShopDTO dto);

    Result queryShopByName(String name, Integer current);

    Result deleteShop(Long id);
}

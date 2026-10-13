package com.hmdp.api.client;

import com.hmdp.api.client.fallback.ShopClientFallback;
import com.hmdp.api.config.DefaultFeignConfig;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.ShopTypeDTO;
import com.hmdp.common.search.ShopSearchDocDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * shop-service 的「搜索文档反查」契约（供 search-service 建索引使用）。与 {@link BlogClient} 同理：{@code GET /shop/{id}} 也有 {@code viewCounter.asyncIncr} 副作用，不能当反查口子，故另开 {@code /shop/search-doc/**}（不计数、带分类名）。
 */
@FeignClient(value = "shop-service",
        configuration = DefaultFeignConfig.class,
        fallbackFactory = ShopClientFallback.class)
public interface ShopClient {

    /**
     * @return {@code success=true, data=null} = 店铺确实不存在；{@code success=false} = 调用失败（需重试）
     */
    @GetMapping("/shop/search-doc/{id}")
    Result<ShopSearchDocDTO> getSearchDoc(@PathVariable("id") Long id);

    @GetMapping("/shop/search-doc/page")
    Result<List<ShopSearchDocDTO>> pageSearchDoc(@RequestParam("current") Integer current,
                                                 @RequestParam("size") Integer size);

    /**
     * 查全部分类（复用既有 {@code GET /shop-type/list}，无需 shop-service 新增接口）。search-service 用它把「分类改名」广播翻译成新分类名，再对 {@code hmdp_shop} 做 {@code _update_by_query(term: typeId)} 局部刷新。
     */
    @GetMapping("/shop-type/list")
    Result<List<ShopTypeDTO>> listShopTypes();
}

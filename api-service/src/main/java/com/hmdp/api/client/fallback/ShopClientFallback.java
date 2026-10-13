package com.hmdp.api.client.fallback;

import com.hmdp.api.client.ShopClient;
import com.hmdp.common.domain.Result;
import com.hmdp.common.domain.ShopTypeDTO;
import com.hmdp.common.search.ShopSearchDocDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * 同 {@link BlogClientFallback}：降级用 success=false 表达「调用失败」，与「店铺已不存在（success=true + data=null）」严格区分。
 */
@Slf4j
public class ShopClientFallback implements FallbackFactory<ShopClient> {

    @Override
    public ShopClient create(Throwable cause) {
        return new ShopClient() {
            @Override
            public Result<ShopSearchDocDTO> getSearchDoc(Long id) {
                log.error("远程调用 ShopClient#getSearchDoc 失败，shopId={}", id, cause);
                return Result.fail("反查店铺搜索文档失败");
            }

            @Override
            public Result<List<ShopSearchDocDTO>> pageSearchDoc(Integer current, Integer size) {
                log.error("远程调用 ShopClient#pageSearchDoc 失败，current={}, size={}", current, size, cause);
                return Result.fail("分页反查店铺搜索文档失败");
            }

            @Override
            public Result<List<ShopTypeDTO>> listShopTypes() {
                log.error("远程调用 ShopClient#listShopTypes 失败", cause);
                return Result.fail("查询店铺分类失败");
            }
        };
    }
}

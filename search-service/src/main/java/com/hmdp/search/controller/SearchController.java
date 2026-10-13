package com.hmdp.search.controller;

import com.hmdp.common.domain.Result;
import com.hmdp.search.domain.vo.SearchHitVO;
import com.hmdp.search.domain.vo.SearchPageVO;
import com.hmdp.search.domain.vo.ShopHitVO;
import com.hmdp.search.service.ISearchHealthService;
import com.hmdp.search.service.ISearchQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 站内搜索接口（免登录，走网关白名单）。护栏（size 钳制 / 深分页拒绝 / 空 key 返回空）在 SearchQueryService 内。 */
@Slf4j
@Tag(name = "站内搜索", description = "文章搜索 / 店铺搜索（兼分类搜索）/ 搜索联想")
@RestController
@RequestMapping("/search")
@RequiredArgsConstructor
public class SearchController {

    private final ISearchQueryService queryService;

    private final ISearchHealthService healthService;

    @Operation(summary = "文章搜索")
    @GetMapping("/blog")
    public Result searchBlog(
            @RequestParam(value = "key", required = false) @Parameter(description = "关键词") String key,
            @RequestParam(value = "page", defaultValue = "1") @Parameter(description = "页码") Integer page,
            @RequestParam(value = "size", defaultValue = "10") @Parameter(description = "每页数量，上限 50") Integer size,
            @RequestParam(value = "sort", defaultValue = "relevance")
            @Parameter(description = "relevance|time|hot") String sort,
            @RequestParam(value = "shopId", required = false) @Parameter(description = "按店铺筛选") Long shopId,
            @RequestParam(value = "authorId", required = false) @Parameter(description = "按作者筛选") Long authorId) {
        SearchPageVO<SearchHitVO> data = queryService.searchBlog(key, page, size, sort, shopId, authorId);
        return Result.ok(data);
    }

    @Operation(summary = "店铺搜索（兼分类搜索：typeName 参与检索，搜品类词也能出店）")
    @GetMapping("/shop")
    public Result searchShop(
            @RequestParam(value = "key", required = false) @Parameter(description = "关键词") String key,
            @RequestParam(value = "page", defaultValue = "1") @Parameter(description = "页码") Integer page,
            @RequestParam(value = "size", defaultValue = "10") @Parameter(description = "每页数量，上限 50") Integer size,
            @RequestParam(value = "sort", defaultValue = "relevance")
            @Parameter(description = "relevance|price|sold|score") String sort,
            @RequestParam(value = "typeId", required = false) @Parameter(description = "按分类筛选") Long typeId,
            @RequestParam(value = "x", required = false) @Parameter(description = "经度") Double x,
            @RequestParam(value = "y", required = false) @Parameter(description = "纬度") Double y,
            @RequestParam(value = "radius", required = false) @Parameter(description = "半径（米）") Integer radius) {
        SearchPageVO<ShopHitVO> data = queryService.searchShop(key, page, size, sort, typeId, x, y, radius);
        return Result.ok(data);
    }

    @Operation(summary = "搜索联想")
    @GetMapping("/suggest")
    public Result suggest(
            @RequestParam(value = "prefix", required = false) @Parameter(description = "前缀") String prefix,
            @RequestParam(value = "type", defaultValue = "blog")
            @Parameter(description = "blog|shop") String type) {
        List<String> suggestions = queryService.suggest(prefix, type);
        return Result.ok(suggestions);
    }

    /**
     * 健康检查：返回 ES 版本与索引状态（非敏感信息），用于确认 7.12.1 与索引是否正常。
     */
    @Operation(summary = "健康检查（含 ES 版本，用于确认 7.12.1 与索引状态）")
    @GetMapping("/health")
    public Result health() {
        try {
            return Result.ok(healthService.health());
        } catch (Exception e) {
            log.error("搜索服务健康检查失败", e);
            return Result.fail("Elasticsearch 不可用");
        }
    }
}

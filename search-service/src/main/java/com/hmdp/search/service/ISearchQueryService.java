package com.hmdp.search.service;

import com.hmdp.search.domain.vo.SearchHitVO;
import com.hmdp.search.domain.vo.SearchPageVO;
import com.hmdp.search.domain.vo.ShopHitVO;

import java.util.List;

/** 站内搜索查询：文章搜索 / 店铺搜索 / 搜索联想。免登录接口的三道护栏（size 钳制 / 深分页拒绝 / 空 key 返回空）在此实现。 */
public interface ISearchQueryService {

    SearchPageVO<SearchHitVO> searchBlog(String key, Integer page, Integer size,
                                         String sort, Long shopId, Long authorId);

    SearchPageVO<ShopHitVO> searchShop(String key, Integer page, Integer size, String sort,
                                      Long typeId, Double x, Double y, Integer radius);

    List<String> suggest(String prefix, String type);
}

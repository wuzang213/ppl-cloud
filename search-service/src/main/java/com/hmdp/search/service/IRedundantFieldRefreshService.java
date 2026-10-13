package com.hmdp.search.service;

/** 冗余字段刷新（作者改名 / 分类改名）：用合并窗口收敛成一次 _update_by_query，失败放回待处理集合下一轮重试，不走死信表。 */
public interface IRedundantFieldRefreshService {

    void scheduleUser(Long userId);

    void scheduleShopType(Long typeId);
}

package com.hmdp.search.service;

import com.hmdp.search.domain.vo.ConsistencyReport;

/** 索引一致性校验与修复：只比对 id 集合（成本极低、可每天跑），repair 幂等（删不存在的文档被忽略、写同 id 覆盖）。 */
public interface ISearchVerifyService {

    ConsistencyReport verify(String type);

    ConsistencyReport repair(String type);
}

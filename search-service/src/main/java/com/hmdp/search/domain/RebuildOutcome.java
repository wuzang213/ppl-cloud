package com.hmdp.search.domain;

/** 一次重建请求的结局。刻意不用 {@code boolean}：三种结局（没抢到锁没跑 / 跑了且成功 / 跑了但失败）压成布尔会丢信息 ——
 * {@code false} 会同时表示"跳过"和"失败"，一次真实失败会被接口说成"已有同类型的重建任务在执行"，运维不再跟进；故对外响应改为 {@code status}（本枚举）+ {@code triggered}。 */
public enum RebuildOutcome {

    /** 已有重建在跑（Redis 租约锁没抢到），本次<b>没有执行</b>任何重建动作 */
    SKIPPED,

    /** 重建执行完毕：一致性校验通过，别名已原子切换 */
    SUCCESS,

    /** 重建执行了但中途失败：半成品索引已清理，别名保持在原来的索引上（搜索不受影响） */
    FAILED
}

package com.hmdp.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * search-service 配置项（Nacos: search-service.yaml，前缀 hmdp.search）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hmdp.search")
public class SearchProperties {

    /** ES 地址（单节点） */
    private String host = "127.0.0.1";

    private int port = 9200;

    private int connectTimeoutMs = 3000;

    private int socketTimeoutMs = 15000;

    /** 全量重建分页大小 */
    private int rebuildPageSize = 200;

    /** 单次 bulk 提交的文档数 */
    private int bulkSize = 500;

    /** 启动时若别名/索引缺失或为空，是否自动触发全量重建 */
    private boolean autoRebuildOnStartup = true;

    /** 重建互斥锁租约（分钟），超时自动释放防止死锁 */
    private int rebuildLockMinutes = 30;

    /** 死信重试上限，超过转终态失败等人工介入（与 outbox 的 MAX_RETRY=5 对齐） */
    private int failedRetryMax = 5;

    /** 是否使用 IK 分词（已确认装 {@code analysis-ik} 7.12.1，正常保持 true）。
     * 置 false 会把 mapping 的 {@code ik_max_word}/{@code ik_smart} 降级为 {@code standard} —— 给「插件没装/装错版本导致建索引失败」留的降级开关：中文退化为单字切分、质量下降但服务能起来。 */
    private boolean useIk = true;

    /** 冗余字段刷新的防抖窗口（毫秒），同 SHOP_TYPE 短时间内多次变更只刷一次 */
    private int debounceMillis = 5000;

    /** 死信重试任务扫描间隔（毫秒） */
    private int retryIntervalMillis = 60000;

    /** 除「别名当前指向的索引」外，额外保留几个历史版本用于回滚，默认 1 个。
     * 保留的是「刚从别名上摘下来的」（真正的回滚候选），不是「版本号最大的孤儿」—— 后者会把被 kill 的半成品当回滚候选留着，反而删掉能回滚的那个。 */
    private int keepOldVersions = 1;

    // ==================== 查询护栏（免登录接口必须做）====================

    /** size 硬上限，防 size=10000 一次拉全库 */
    private int maxPageSize = 50;

    /** from + size 上限，防深翻页打爆堆内存 */
    private int maxWindow = 1000;
}

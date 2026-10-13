package com.hmdp.search.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 索引一致性校验报告。{@code esOnly} = ES 有业务库没有（该删未删，多因绕过业务代码直接 DELETE 表，残留窗口 = 对账周期默认 1 天）；
 * {@code businessOnly} = 业务库有 ES 没有（漏索引，多因消费失败重试耗尽或重建期间业务服务短暂不可用）。 */
@Data
public class ConsistencyReport implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;

    private long businessCount;

    private long esCount;

    /** ES 多出来的 id（应删除） */
    private List<Long> esOnly;

    /** 业务多出来的 id（应补索引） */
    private List<Long> businessOnly;

    private boolean consistent;

    /** 差异是否已被自动修复（verify 接口为 false，repair 为 true） */
    private boolean repaired;
}

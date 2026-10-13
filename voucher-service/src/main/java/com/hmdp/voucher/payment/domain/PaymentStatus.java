package com.hmdp.voucher.payment.domain;

/** 支付单状态机（唯一权威定义；DDL 脚本头注释有等价描述）：0 PENDING（预下单已创建）→ 1 SUCCESS / 2 FAILED /
 * 3 CLOSED（订单超时关单时 closeTimeoutOrder 追加关闭）。合法迁移只有 {@code 0→1 / 0→2 / 0→3}；SUCCESS 与 CLOSED
 * 互斥 —— 订单关单后到达的成功回调必须被拒绝（资损场景），不允许 {@code 3→1}；重复回调落 {@code 1→1}，必须不重复写库、不重复发消息。 */
public enum PaymentStatus {

    PENDING(0, "待支付"),
    SUCCESS(1, "支付成功"),
    FAILED(2, "支付失败"),
    CLOSED(3, "已关闭");

    private final int value;
    private final String desc;

    PaymentStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public int getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按落库值反查枚举；未知值返回 {@code null}（调用方自行决定是报错还是当异常数据处理）。
     */
    public static PaymentStatus of(Integer value) {
        if (value == null) {
            return null;
        }
        for (PaymentStatus s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        return null;
    }

    /**
     * 是否终态。终态支付单不再接受任何推进（幂等守卫的判据）。
     */
    public boolean isTerminal() {
        return this != PENDING;
    }

    /** 是否允许从当前状态推进到 {@code target}。唯一合法来源是 {@link #PENDING}，且目标必须是终态之一。 */
    public boolean canAdvanceTo(PaymentStatus target) {
        return this == PENDING && target != null && target != PENDING;
    }
}

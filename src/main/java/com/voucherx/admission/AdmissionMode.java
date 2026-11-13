package com.voucherx.admission;

/**
 * 准入实验模式，application.yml 默认 OFF；选择 ADAPTIVE 只启用已有控制逻辑，不代表性能验收已完成。
 */
public enum AdmissionMode {
    /**
     * 跳过本地和全局准入；自适应调度也直接返回。
     */
    OFF,
    /**
     * 启用配置允许的本地保护和 Redis 固定共享/活动预算。
     */
    FIXED,
    /**
     * 请求侧仍经两层预算，反馈控制器周期调整 Redis 共享预算；活动配额保持固定。
     */
    ADAPTIVE
}

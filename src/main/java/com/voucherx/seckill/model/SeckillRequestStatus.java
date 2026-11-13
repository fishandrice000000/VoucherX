package com.voucherx.seckill.model;

/**
 * 请求跟踪状态；当前查询主要由 MySQL phase 映射，部分值也用于 Redis 观测，勿与订单付款状态混用。
 */
public enum SeckillRequestStatus {
    /**
     * 等待处理的辅助状态；当前持久化主线的初始阶段是 INTENT。
     */
    PENDING,
    /**
     * 已持久化受理，等待或正在异步建单；不保证此刻已经发到 MQ。
     */
    PROCESSING,
    /**
     * 购买订单已创建；尚不能据此认定付款或发券成功。
     */
    SUCCESS,
    /**
     * 建单请求已经关闭或确认业务失败。
     */
    FAILED,
    /**
     * 中间操作结果尚未确定，需要按持久化证据核对。
     */
    UNKNOWN,
    /**
     * Redis 预留已释放的辅助跟踪状态；持久化账本另有 reservation 字段。
     */
    RELEASED;

    /**
     * 兼容解析缓存中的状态文本；空白或未知值按 UNKNOWN 处理。
     *
     * @param value
     *                  状态字符串，区分大小写
     *
     * @return 解析后的请求状态
     */
    public static SeckillRequestStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return SeckillRequestStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}

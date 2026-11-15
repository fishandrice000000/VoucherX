package com.voucherx.seckill.model;

/**
 * 对外请求跟踪状态，由 MySQL phase 映射；Redis 预留状态和订单付款状态分别表达其他业务事实。
 */
public enum SeckillRequestStatus {
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
    UNKNOWN;

}

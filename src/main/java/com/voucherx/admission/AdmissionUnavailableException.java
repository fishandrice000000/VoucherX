package com.voucherx.admission;

/**
 * 封装 Redis 准入数据访问失败，由编排层转换为 HTTP 503，表示本次准入依赖不可用。
 */
public class AdmissionUnavailableException extends RuntimeException {

    /**
     * 携带准入依赖故障的说明和原始原因，不在异常构造时操作任何预算。
     *
     * @param message
     *                    故障说明
     * @param cause
     *                    底层 Redis 数据访问异常
     */
    public AdmissionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

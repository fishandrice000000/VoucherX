package com.voucherx.admission;

/**
 * 准入基础设施不可用。与“令牌不足”的 429 不同，调用方应返回 503，不能静默放行。
 */
public class AdmissionUnavailableException extends RuntimeException {

    public AdmissionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

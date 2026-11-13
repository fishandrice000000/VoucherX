package com.voucherx.common.exception;

import com.voucherx.common.api.ErrorCode;

/**
 * 可预期的业务异常。未知异常不应使用该类型，以免把系统故障误报成业务失败。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 用错误类型的默认消息创建已分类业务异常；HTTP 包装由 GlobalExceptionHandler 完成。
     *
     * @param errorCode
     *                      包含 HTTP 状态和响应体业务码的错误类型
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /**
     * 用已分类错误类型及具体提示创建业务异常；不在构造时执行补偿或重试。
     *
     * @param errorCode
     *                      已判定错误类型
     * @param message
     *                      本次业务错误的具体提示
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 保留底层原因并暴露已分类的业务语义；02 将 Redis 准入故障用此形式转换为 503。
     *
     * @param errorCode
     *                      对外错误类型
     * @param message
     *                      对用户的错误提示
     * @param cause
     *                      保留供排查的原始异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 供统一异常处理器读取 HTTP 状态和业务码分类。
     *
     * @return 构造时提供的错误类型
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}

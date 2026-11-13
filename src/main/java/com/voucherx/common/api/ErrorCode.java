package com.voucherx.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。code 是稳定的业务标识，HTTP 状态码用于通用语义表达。
 */
public enum ErrorCode {

    INVALID_PARAM(400, 40000, "请求参数不合法"), UNAUTHORIZED(401, 40100, "未登录或登录已过期"), FORBIDDEN(403, 40300,
            "无权访问该资源"), NOT_FOUND(404, 40400, "资源不存在"), METHOD_NOT_ALLOWED(405, 40500, "请求方法不支持"), CONFLICT(409, 40900,
                    "请求冲突"), TOO_MANY_REQUESTS(429, 42900, "请求过于频繁，请稍后再试"), INTERNAL_ERROR(500, 50000,
                            "服务器内部错误"), SERVICE_UNAVAILABLE(503, 50300, "服务暂时不可用"),

    AUTH_CODE_INVALID(400, 40010, "验证码错误或已过期"), AUTH_CODE_TOO_FREQUENT(429, 42910, "验证码请求过于频繁"),

    SHOP_NOT_FOUND(404, 40410, "商户不存在"),

    SECKILL_NOT_FOUND(404, 40420, "秒杀活动不存在"), SECKILL_NOT_STARTED(400, 40020, "秒杀尚未开始"), SECKILL_ENDED(400, 40021,
            "秒杀已结束"), SECKILL_SOLD_OUT(409, 40920, "优惠券库存不足"), SECKILL_DUPLICATE(409, 40921,
                    "请勿重复抢购"), SECKILL_STOCK_UNAVAILABLE(503, 50320, "秒杀库存状态不可用，请稍后重试"), SECKILL_PUBLISH_FAILED(503,
                            50321, "秒杀请求受理失败，请稍后重试"),
    /**
     * 02 预算不足：HTTP 429、业务码 42920，表示系统当前准入预算不足，不是活动库存售罄。
     */
    SECKILL_BUSY(429, 42920,
            "系统繁忙，请稍后重试"),
    /**
     * 02 策略或 Redis 准入依赖不可用：HTTP 503、业务码 50322，不能按正常放行处理。
     */
    SECKILL_ADMISSION_UNAVAILABLE(503, 50322, "秒杀准入服务暂不可用，请稍后重试"),

    REQUEST_NOT_FOUND(404, 40430, "秒杀请求不存在"), ORDER_NOT_FOUND(404, 40440, "订单不存在");

    private final int httpStatus;
    private final int code;
    private final String message;

    ErrorCode(int httpStatus, int code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    /**
     * 把枚举中保存的 HTTP 数字转换为 Spring HttpStatus，供统一异常处理器设置响应状态。
     *
     * @return HTTP 状态枚举，与响应体业务码分开
     */
    public HttpStatus httpStatus() {
        return HttpStatus.valueOf(httpStatus);
    }

    /**
     * 读取稳定的业务错误标识，例如 SECKILL_BUSY 的 42920；此数字不直接用作 HTTP 状态。
     *
     * @return 响应体 code 字段的业务错误码
     */
    public int code() {
        return code;
    }

    /**
     * 读取该错误类型的默认展示消息，业务异常也可以提供更具体的提示。
     *
     * @return 默认错误说明
     */
    public String message() {
        return message;
    }
}

package com.voucherx.common.exception;

import com.voucherx.common.api.ApiResponse;
import com.voucherx.common.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 把 HTTP 处理期间抛出的异常统一包装为 ApiResponse，并设置对应的 HTTP 状态。
 * <p>
 * 02 的 SECKILL_BUSY 对应 HTTP 429、业务码 42920；策略或准入依赖不可用对应 HTTP 503、业务码 50322。
 * 该层只组织错误响应，不补偿库存、不归还令牌，也不把 MQ 线程异常转换成 HTTP 响应。
 *
 * @see com.voucherx.admission.SeckillAdmissionService
 * @see com.voucherx.common.api.ErrorCode
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 读取异常中的 ErrorCode 设置 HTTP 状态，业务码和异常消息写入统一响应体；不吞成 HTTP 200。
     *
     * @param ex
     *               服务层已分类的业务异常
     * @return 携带分类状态和业务错误内容的 HTTP 响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode errorCode = ex.errorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.error(errorCode.code(), ex.getMessage()));
    }

    /**
     * 将请求体 Bean Validation 的字段错误拼接成提示文本，再包装为 400 参数错误。
     *
     * @param ex
     *               请求对象字段校验异常
     * @return HTTP 400 的统一错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return badRequest(message);
    }

    /**
     * 将方法参数约束失败的属性路径及原因拼接，包装为 400 参数错误。
     *
     * @param ex
     *               参数约束异常
     * @return HTTP 400 的统一错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("；"));
        return badRequest(message);
    }

    /**
     * 对缺少请求参数、无法读取请求体或参数类型不符返回统一格式错误提示。
     *
     * @param ex
     *               Spring MVC 解析参数时的异常
     * @return HTTP 400 的统一错误响应
     */
    @ExceptionHandler({ MissingServletRequestParameterException.class, HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class })
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception ex) {
        return badRequest("请求参数格式不正确");
    }

    /**
     * 将不支持的 HTTP 方法映射为 METHOD_NOT_ALLOWED。
     *
     * @param ex
     *               请求方法不匹配异常
     * @return HTTP 405 的统一错误响应
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        ErrorCode code = ErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), code.message()));
    }

    /**
     * 把数据库唯一约束冲突转换为资源或幂等标识冲突，不向客户端暴露数据库异常细节。
     *
     * @param ex
     *               HTTP 调用链中未在业务层处理的重复键异常
     * @return HTTP 409 的统一错误响应
     */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(org.springframework.dao.DuplicateKeyException ex) {
        ErrorCode code = ErrorCode.CONFLICT;
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.error(code.code(), "资源或幂等标识冲突"));
    }

    /**
     * 记录未分类异常后返回通用内部错误消息，响应不携带堆栈。
     *
     * @param ex
     *               未被其他异常处理方法匹配的异常
     * @return HTTP 500 的统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex) {
        log.error("未处理的服务器异常", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(code.code(), code.message()));
    }

    /**
     * 构造参数错误响应；传入消息为空白时使用 INVALID_PARAM 默认提示。
     *
     * @param message
     *                    对用户展示的参数错误说明，可为空
     * @return HTTP 400 的统一错误响应
     */
    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        ErrorCode code = ErrorCode.INVALID_PARAM;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), message == null || message.isBlank() ? code.message() : message));
    }

    /**
     * 把一个字段校验错误转换为“字段名 + 原因”的展示文本。
     *
     * @param error
     *                  Bean Validation 字段错误
     * @return 可拼接到 400 响应中的提示文本
     */
    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}

package com.voucherx.auth.controller;

import com.voucherx.auth.dto.CodeResponse;
import com.voucherx.auth.dto.LoginRequest;
import com.voucherx.auth.dto.LoginResponse;
import com.voucherx.auth.dto.SendCodeRequest;
import com.voucherx.auth.dto.UserResponse;
import com.voucherx.auth.interceptor.TokenExtractor;
import com.voucherx.auth.service.AuthService;
import com.voucherx.common.api.ApiResponse;
import com.voucherx.auth.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入门链路的登录入口：申请验证码、登录，再把 Token 交给客户端保存。
 * <p>
 * 后续抢券请求的用户 ID 来自认证拦截器写入的线程上下文，客户端不需要提交 userId。
 * 验证码和登录接口公开；查询当前用户与退出登录接口需要认证。
 *
 * @see com.voucherx.auth.service.AuthService
 * @see com.voucherx.config.WebConfig
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 接收手机号并以 HTTP 远端地址作为发送频控依据；实际生成及缓存验证码交给服务层。
     *
     * @param request
     *                        通过校验的手机号请求
     * @param httpRequest
     *                        用于读取远端 IP 的 HTTP 请求
     *
     * @return 验证码有效期；仅开启调试配置时包含验证码
     */
    @PostMapping("/code")
    public ApiResponse<CodeResponse> sendCode(@Valid @RequestBody SendCodeRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.sendCode(request.phone(), httpRequest.getRemoteAddr()));
    }

    /**
     * 校验并消费验证码，返回后续受保护接口使用的 Token。
     *
     * @param request
     *                    手机号和本次收到的验证码
     *
     * @return Token 及数据库中的用户身份
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.phone(), request.code()));
    }

    /**
     * 从当前请求的登录上下文取得 userId，再查询用户资料。
     *
     * @return 当前登录用户的公开资料
     */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.ok(authService.currentUser(UserContext.getRequired()));
    }

    /**
     * 删除本次请求携带的 Token 对应的 Redis 登录态。
     *
     * @param request
     *                    已经过认证的 HTTP 请求
     *
     * @return 无业务数据的成功响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(TokenExtractor.extract(request));
        return ApiResponse.ok();
    }
}

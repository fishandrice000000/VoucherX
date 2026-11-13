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

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/code")
    public ApiResponse<CodeResponse> sendCode(@Valid @RequestBody SendCodeRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.sendCode(request.phone(), httpRequest.getRemoteAddr()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.phone(), request.code()));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.ok(authService.currentUser(UserContext.getRequired()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(TokenExtractor.extract(request));
        return ApiResponse.ok();
    }
}

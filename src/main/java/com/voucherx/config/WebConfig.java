package com.voucherx.config;

import com.voucherx.auth.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置入门链路的认证范围；在此确认请求进入 Controller 前是否会经过登录拦截器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 对 /api 下的接口启用认证，仅放行发码、登录及公开商户查询。
     *
     * @param registry
     *                     Spring MVC 提供的拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/code",
                        "/api/auth/login",
                        "/api/shops/**");
    }
}

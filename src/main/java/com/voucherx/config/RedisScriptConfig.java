package com.voucherx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Redis Lua 脚本集中注册，避免脚本内容散落在业务代码中。
 */
@Configuration
public class RedisScriptConfig {

    @Bean
    public DefaultRedisScript<Long> consumeAuthCodeScript() {
        return script("lua/auth_code_consume.lua");
    }

    @Bean
    public DefaultRedisScript<Long> shopLockReleaseScript() {
        return script("lua/shop_lock_release.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillReserveScript() {
        return script("lua/seckill_reserve.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillReleaseScript() {
        return script("lua/seckill_release.lua");
    }

    private DefaultRedisScript<Long> script(String classpath) {
        try {
            ClassPathResource resource = new ClassPathResource(classpath);
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(content, Long.class);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Redis Lua 脚本失败: " + classpath, ex);
        }
    }
}

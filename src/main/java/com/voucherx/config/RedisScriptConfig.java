package com.voucherx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把 classpath 中的 Lua 注册为有名称的 Spring Bean，业务服务通过 Qualifier 注入对应脚本。
 * <p>
 * 01 从 seckillReserveScript 读到 resources/lua/seckill_reserve.lua，再回到库存服务对照
 * KEYS/ARGV。
 * 加载只读取脚本文本；真正执行发生在 StringRedisTemplate.execute 时，不发生在应用装配阶段。
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

    /**
     * 注册库存预留脚本：时间/门禁/资格检查和库存、归属、请求状态写入在一次 Redis 脚本调用内完成。
     *
     * @return 期望整数返回值的预留脚本 Bean
     */
    @Bean
    public DefaultRedisScript<Long> seckillReserveScript() {
        return script("lua/seckill_reserve.lua");
    }

    /**
     * 注册预留释放脚本；脚本校验归属并幂等返还额度，调用前的数据库关闭屏障由补偿流程负责。
     *
     * @return 期望整数返回值的释放脚本 Bean
     */
    @Bean
    public DefaultRedisScript<Long> seckillReleaseScript() {
        return script("lua/seckill_release.lua");
    }

    @Bean
    public DefaultRedisScript<Long> seckillRequestStatusScript() {
        return script("lua/seckill_request_status.lua");
    }

    /**
     * 注册共享/活动双桶获取脚本；缺失固定策略初始化、按时间结算、双桶判断和条件扣减在一次 Redis 调用中完成。
     *
     * @return 期望 Long 结果的准入 Lua Bean
     */
    @Bean
    public DefaultRedisScript<Long> admissionAcquireScript() {
        return script("lua/admission_acquire.lua");
    }

    /**
     * 注册共享策略更新脚本；按旧速率结算后更新速率、容量及截止，不在每次续期时赠送满桶。
     *
     * @return 期望 Long 结果的策略 Lua Bean
     */
    @Bean
    public DefaultRedisScript<Long> admissionPolicyUpdateScript() {
        return script("lua/admission_policy_update.lua");
    }

    @Bean
    public DefaultRedisScript<Long> recoveryPublishScript() {
        return script("lua/recovery_publish.lua");
    }

    /**
     * 从 classpath 以 UTF-8 加载脚本并声明 Long 返回类型；资源加载失败时停止对应 Bean 创建。
     *
     * @param classpath
     *                      Lua 资源路径，例如 lua/seckill_reserve.lua
     * @return 尚未执行的 Lua 脚本对象
     * @throws IllegalStateException
     *                                   资源不存在或读取发生 I/O 异常
     */
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

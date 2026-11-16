package com.voucherx.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 将 classpath 中的 Lua 注册为具名 Spring Bean，业务服务通过 Qualifier 注入。
 * <p>
 * 从 seckillReserveScript 可继续阅读 seckill_reserve.lua，再回到库存服务对照 KEYS 和 ARGV。
 * 脚本由 RedisTemplate.execute 调用，Bean 初始化负责加载脚本文本。
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
     * 注册共享策略更新脚本；先按生效中的速率结算令牌，再设置目标速率、容量及截止时刻。
     *
     * @return 期望 Long 结果的策略 Lua Bean
     */
    @Bean
    public DefaultRedisScript<Long> admissionPolicyUpdateScript() {
        return script("lua/admission_policy_update.lua");
    }

    /**
     * 注册活动恢复的原子发布脚本，对照 RecoveryService.rebuild 阅读 KEYS/ARGV。
     * <p>
     * 七个 KEYS 依次为正式库存、用户资格、归属、gate 及三组对应暂存键；
     * 四个 ARGV 为 epoch、recoveryId、可用额度 A 和成员数量 R+C。
     * Lua 校验冻结代次、暂存库存及成员数后，一次替换库存、资格和归属并保存发布标记，门禁保持冻结。
     * <p>
     * 返回 1 为成功，-1 为门禁不符，-2 为暂存库存不符，-3 为成员数不符。
     * 逐条请求 Hash 和 MySQL 事务由恢复服务分别管理。
     *
     * @return 期望 Long 结果的恢复发布 Lua Bean
     * @see com.voucherx.recovery.RecoveryService
     */
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

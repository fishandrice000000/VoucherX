package com.voucherx.seckill.service;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.SeckillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

/**
 * Redis 秒杀预留的脚本适配层；库存、用户资格、请求状态和归属证据由 Lua 一次原子处理。
 * <p>
 * 这里的原子性只覆盖一次 Redis 脚本，不覆盖 MySQL 事务或 RabbitMQ。
 * 主线读 reserve，并对照 resources/lua/seckill_reserve.lua；数据库最终扣库存发生在异步建单事务。
 */
@Service
public class SeckillStockService {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockService.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> seckillReserveScript;
    private final DefaultRedisScript<Long> seckillReleaseScript;

    public SeckillStockService(StringRedisTemplate redisTemplate,
            @Qualifier("seckillReserveScript") DefaultRedisScript<Long> seckillReserveScript,
            @Qualifier("seckillReleaseScript") DefaultRedisScript<Long> seckillReleaseScript) {
        this.redisTemplate = redisTemplate;
        this.seckillReserveScript = seckillReserveScript;
        this.seckillReleaseScript = seckillReleaseScript;
    }

    /**
     * 将活动库存键、请求身份和布局参数交给一次 Redis 预留脚本。
     * <p>
     * KEYS 依次为 stock、users、request、owners、gate；ARGV 依次为
     * userId、requestId、nowMillis、beginMillis、endMillis、voucherId、stockMode、startBucketNo、layoutVersion、epoch。
     * <p>
     * Lua 校验完整门禁与参数，识别同请求重放，再检查库存、活动时间和用户资格；通过后原子扣减库存、记录资格、归属和请求状态，并 PERSIST 活跃请求。
     * 同一请求的重复预留返回当前余量。消息起始桶随请求记录，实际 MySQL 扣减桶由订单事务确定。
     * <p>
     * 非负值表示预留成功，0 表示余量恰好为零；-1 表示库存不可用、-2 未开始、-3 已结束、-4 重复用户、-5 售罄、-6 门禁或身份布局参数不符。
     * 连接异常或空返回抛业务异常，由调用者保留结果未知的意图并安排查证。
     *
     * @param voucherId
     *                          活动 ID
     * @param userId
     *                          已认证用户 ID
     * @param requestId
     *                          已提交 MySQL 意图的请求 ID
     * @param nowMillis
     *                          编排层取得的当前 Unix 毫秒
     * @param beginMillis
     *                          活动开始 Unix 毫秒
     * @param endMillis
     *                          活动结束 Unix 毫秒
     * @param stockMode
     *                          SINGLE 或 BUCKET
     * @param startBucketNo
     *                          计划起始 MySQL 桶号；只是随请求记录，不用于 Redis 分桶
     * @param layoutVersion
     *                          绑定的库存布局版本
     * @param epoch
     *                          意图中的活动恢复代次
     * @return 预扣后的或同请求重放时观察到的当前余量；负数为明确拒绝码
     * @see RedisKeys
     * @see com.voucherx.config.RedisScriptConfig#seckillReserveScript()
     */
    public long reserve(Long voucherId, Long userId, String requestId,
            long nowMillis, long beginMillis, long endMillis,
            String stockMode, int startBucketNo, int layoutVersion, long epoch) {
        try {
            Long result = redisTemplate.execute(
                    seckillReserveScript,
                    List.of(RedisKeys.seckillStock(voucherId),
                            RedisKeys.seckillUsers(voucherId),
                            RedisKeys.seckillRequest(requestId),
                            RedisKeys.seckillOwners(voucherId), RedisKeys.seckillGate(voucherId)),
                    String.valueOf(userId),
                    requestId,
                    String.valueOf(nowMillis),
                    String.valueOf(beginMillis),
                    String.valueOf(endMillis),
                    String.valueOf(voucherId),
                    stockMode,
                    String.valueOf(startBucketNo),
                    String.valueOf(layoutVersion), String.valueOf(epoch));
            if (result == null) {
                throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
            }
            return result;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE, "Redis 库存操作失败", ex);
        }
    }

    /**
     * 执行原请求的 Redis 预留释放；调用前须已提交数据库关闭屏障，并持有活动、请求及补偿保护锁。
     * <p>
     * KEYS 为 stock、users、request、owners、gate；ARGV 为 userId、reason、requestId、epoch。
     * 脚本检查当前门禁、请求身份和预留归属；RELEASED 返回 2，SUCCESS 拒绝释放。
     * 待释放请求的归属及库存均有效时，原子移除资格和归属、增加一份库存，并写 RELEASED。
     * <p>
     * 返回 1 或 2 均表示已达到释放结果，映射 true；负码、空返回或数据访问异常映射 false，补偿服务保留进度后继续查证或重试。
     * 例如 R1 释放后用户又持有 R2，重复执行 R1 通过自身 RELEASED 标记返回，R2 的资格与归属保持有效。
     *
     * @param voucherId
     *                      原活动 ID
     * @param userId
     *                      原预留用户 ID
     * @param requestId
     *                      原请求 ID，重试保持不变
     * @param reason
     *                      新释放时保存的原因
     * @param epoch
     *                      与当前请求及活动核对的恢复代次
     * @return 已达到该请求释放结果时为 true，否则由调用方重试或隔离
     * @see com.voucherx.reconcile.CompensationService#execute
     * @see com.voucherx.common.redis.RedisKeys
     */
    public boolean release(Long voucherId, Long userId, String requestId, String reason, long epoch) {
        try {
            Long result = redisTemplate.execute(
                    seckillReleaseScript,
                    List.of(RedisKeys.seckillStock(voucherId),
                            RedisKeys.seckillUsers(voucherId),
                            RedisKeys.seckillRequest(requestId),
                            RedisKeys.seckillOwners(voucherId), RedisKeys.seckillGate(voucherId)),
                    String.valueOf(userId), reason, requestId, String.valueOf(epoch));
            return result != null && (result == 1L || result == 2L);
        } catch (DataAccessException ex) {
            log.error("释放 Redis 秒杀预扣失败，requestId={}", requestId, ex);
            return false;
        }
    }

    /**
     * 使用 SET NX 创建缺失的 Redis 总库存键，已有键保持现值。
     * <p>
     * 调用者先通过安全初始化流程核对数据库占用与活动门禁，再提交本次初始化额度。
     *
     * @param voucherId
     *                      活动 ID
     * @param stock
     *                      已经核验可初始化的库存数量
     * @return 本次成功创建键时为 true；键已存在或操作失败时为 false
     */
    public boolean initializeIfAbsent(Long voucherId, int stock) {
        try {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(RedisKeys.seckillStock(voucherId), String.valueOf(stock));
            return Boolean.TRUE.equals(created);
        } catch (DataAccessException ex) {
            log.warn("初始化 Redis 秒杀库存失败，voucherId={}", voucherId, ex);
            return false;
        }
    }

    /**
     * 检查 Redis 库存键是否存在；连接异常按 false 返回，后续初始化仍受数据库安全检查约束。
     *
     * @param voucherId
     *                      活动 ID
     * @return 观察到库存键存在时为 true
     */
    public boolean exists(Long voucherId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.seckillStock(voucherId)));
        } catch (DataAccessException ex) {
            return false;
        }
    }

    /**
     * 读取 Redis 当前可用额度，供状态观察和测试断言使用。
     *
     * @param voucherId
     *                      活动 ID
     * @return 剩余库存；键缺失时为 {@code null}
     */
    public Long stock(Long voucherId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.seckillStock(voucherId));
        return value == null ? null : Long.valueOf(value);
    }
}

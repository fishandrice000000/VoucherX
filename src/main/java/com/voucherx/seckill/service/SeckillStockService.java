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
     * 兼容入口，按初始恢复代次 1 执行预留；正常抢券链路使用显式传入代次的重载。
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
     *                          计划起始桶
     * @param layoutVersion
     *                          本次请求绑定的库存布局版本
     *
     * @return Lua 返回值：非负表示成功；负数表示明确拒绝
     */
    public long reserve(Long voucherId, Long userId, String requestId,
            long nowMillis, long beginMillis, long endMillis,
            String stockMode, int startBucketNo, int layoutVersion) {
        return reserve(voucherId, userId, requestId, nowMillis, beginMillis, endMillis, stockMode, startBucketNo,
                layoutVersion, 1);
    }

    /**
     * 把五组 Redis 键和请求身份交给 Lua，同时校验时间、资格、库存及活动门禁。
     * <p>
     * 非负数为预扣后的剩余库存，0 也代表抢到最后一张；负数含义以脚本为准，
     * 包括 -1 未初始化、-2 未开始、-3 已结束、-4 用户重复、-5 售罄、-6 门禁状态或代次不匹配。
     * 同一请求已拥有预留时，脚本直接返回当前余量，不再扣减。
     * 连接异常或空返回会抛业务异常，调用者须把结果视为可能未知，不能盲目释放。
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
     *                          计划起始桶
     * @param layoutVersion
     *                          本次请求绑定的库存布局版本
     * @param epoch
     *                          意图中绑定的恢复代次，防止旧请求在恢复后继续预留
     *
     * @return 预扣后的剩余库存或明确的负数拒绝码
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
     * 兼容入口，以初始恢复代次 1 调用释放脚本；仍须先满足数据库关闭屏障。
     *
     * @param voucherId
     *                      活动 ID
     * @param userId
     *                      原预留用户 ID
     * @param requestId
     *                      原预留请求 ID
     * @param reason
     *                      释放原因
     *
     * @return 脚本完成释放或确认已释放时为 true
     */
    public boolean release(Long voucherId, Long userId, String requestId, String reason) {
        return release(voucherId, userId, requestId, reason, 1);
    }

    /**
     * 按用户、请求和代次核对预留归属后幂等释放；不会无条件增加库存。
     * <p>
     * 调用者应先通过数据库关闭屏障保证旧消息不能再建单。发布超时、NACK 或路由失败本身
     * 都不能替代这一前提；本方法不负责关闭 MySQL 请求。
     * 脚本返回 1 表示本次释放，2 表示此前已释放；Redis 异常时记录日志并返回 false。
     *
     * @param voucherId
     *                      活动 ID
     * @param userId
     *                      原预留用户 ID
     * @param requestId
     *                      原预留请求 ID
     * @param reason
     *                      释放原因
     * @param epoch
     *                      原预留所属的恢复代次
     *
     * @return 已释放时为 true；归属/门禁不满足或 Redis 异常时为 false
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
     * 通过 SET NX 仅初始化缺失的 Redis 总库存，不覆盖已有值。
     * <p>
     * 本方法不检查数据库历史，调用者须先由安全初始化器确认不会重置已有占用。
     *
     * @param voucherId
     *                      活动 ID
     * @param stock
     *                      已经核验可初始化的库存数量
     *
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
     *
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
     * 读取 Redis 剩余库存以便观察；该值不能代替 MySQL 订单或付款事实。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 剩余库存；键缺失时为 {@code null}
     */
    public Long stock(Long voucherId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.seckillStock(voucherId));
        return value == null ? null : Long.valueOf(value);
    }
}

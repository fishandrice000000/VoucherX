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
 * 秒杀库存与领取资格集中在 Redis 中操作，Lua 脚本保证几项操作原子执行。
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
     * 返回 >=0 表示预扣后的剩余库存；负数含义见 Lua 脚本注释。
     */
    public long reserve(Long voucherId, Long userId, String requestId,
                        long nowMillis, long beginMillis, long endMillis,
                        String stockMode, int startBucketNo, int layoutVersion) {
        return reserve(voucherId,userId,requestId,nowMillis,beginMillis,endMillis,stockMode,startBucketNo,layoutVersion,1);
    }
    public long reserve(Long voucherId, Long userId, String requestId,
                        long nowMillis, long beginMillis, long endMillis,
                        String stockMode, int startBucketNo, int layoutVersion,long epoch) {
        try {
            Long result = redisTemplate.execute(
                    seckillReserveScript,
                    List.of(RedisKeys.seckillStock(voucherId),
                            RedisKeys.seckillUsers(voucherId),
                            RedisKeys.seckillRequest(requestId),
                            RedisKeys.seckillOwners(voucherId),RedisKeys.seckillGate(voucherId)),
                    String.valueOf(userId),
                    requestId,
                    String.valueOf(nowMillis),
                    String.valueOf(beginMillis),
                    String.valueOf(endMillis),
                    String.valueOf(voucherId),
                    stockMode,
                    String.valueOf(startBucketNo),
                    String.valueOf(layoutVersion), String.valueOf(epoch)
            );
            if (result == null) {
                throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
            }
            return result;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE, "Redis 库存操作失败", ex);
        }
    }

    /**
     * 仅用于“明确知道消息没有进入队列”的场景，例如 Broker NACK 或路由失败。
     * 发布确认结果未知时禁止调用。
     */
    public boolean release(Long voucherId, Long userId, String requestId, String reason) {
        return release(voucherId,userId,requestId,reason,1);
    }
    public boolean release(Long voucherId,Long userId,String requestId,String reason,long epoch) {
        try {
            Long result = redisTemplate.execute(
                    seckillReleaseScript,
                    List.of(RedisKeys.seckillStock(voucherId),
                            RedisKeys.seckillUsers(voucherId),
                            RedisKeys.seckillRequest(requestId),
                            RedisKeys.seckillOwners(voucherId),RedisKeys.seckillGate(voucherId)),
                    String.valueOf(userId), reason, requestId, String.valueOf(epoch)
            );
            return result != null && (result == 1L || result == 2L);
        } catch (DataAccessException ex) {
            log.error("释放 Redis 秒杀预扣失败，requestId={}", requestId, ex);
            return false;
        }
    }

    /**
     * 只在 Key 不存在时初始化，应用重启不会覆盖已有库存。
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

    public boolean exists(Long voucherId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.seckillStock(voucherId)));
        } catch (DataAccessException ex) {
            return false;
        }
    }

    public Long stock(Long voucherId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.seckillStock(voucherId));
        return value == null ? null : Long.valueOf(value);
    }
}

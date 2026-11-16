package com.voucherx.seckill.service;

import com.voucherx.common.redis.RedisKeys;
import com.voucherx.seckill.model.SeckillRequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记录数据库建单结果的 Redis 辅助快照，供过程观测和对账同步。
 * <p>
 * 消费者在数据库事务提交后尽力写入 SUCCESS 或 FAILED，Lua 负责终态转移保护。
 * 对外查询由 SeckillService 读取 MySQL 请求账本；缓存写入异常记录日志，已提交订单继续有效。
 */
@Service
public class SeckillRequestStatusCache {

    private static final Logger log = LoggerFactory.getLogger(SeckillRequestStatusCache.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> statusScript;

    public SeckillRequestStatusCache(StringRedisTemplate redisTemplate,
            @Qualifier("seckillRequestStatusScript") DefaultRedisScript<Long> statusScript) {
        this.redisTemplate = redisTemplate;
        this.statusScript = statusScript;
    }

    /**
     * 数据库建单事务提交后，将购买订单标识同步到 Redis SUCCESS 快照。
     *
     * @param requestId
     *                      抢券请求 ID
     * @param messageId
     *                      固定业务事件 ID
     * @param orderId
     *                      已提交的购买订单 ID
     */
    public void markSuccess(String requestId, String messageId, Long orderId) {
        update(requestId, SeckillRequestStatus.SUCCESS, messageId, orderId, null);
    }

    /**
     * 账本已确认业务拒绝后写失败缓存。
     *
     * @param requestId
     *                      抢券请求 ID
     * @param messageId
     *                      固定业务事件 ID
     * @param reason
     *                      已确定的业务拒绝原因
     */
    public void markFailed(String requestId, String messageId, String reason) {
        update(requestId, SeckillRequestStatus.FAILED, messageId, null, reason);
    }

    /**
     * 调用终态转移 Lua；请求证据不设置过期时间；数据访问异常只记录日志。
     *
     * @param requestId
     *                      抢券请求 ID
     * @param status
     *                      期望记录的状态
     * @param messageId
     *                      业务事件 ID，可空
     * @param orderId
     *                      购买订单 ID，可空
     * @param reason
     *                      诊断原因，可空
     */
    private void update(String requestId, SeckillRequestStatus status, String messageId,
            Long orderId, String reason) {
        try {
            redisTemplate.execute(
                    statusScript,
                    List.of(RedisKeys.seckillRequest(requestId)),
                    status.name(),
                    messageId == null ? "" : messageId,
                    orderId == null ? "" : String.valueOf(orderId),
                    reason == null ? "" : reason);
        } catch (DataAccessException ex) {
            // 状态缓存失败不能影响已经提交的数据库事务，查询会回源 MySQL。
            log.warn("写入秒杀请求状态失败，requestId={}, status={}", requestId, status, ex);
        }
    }
}

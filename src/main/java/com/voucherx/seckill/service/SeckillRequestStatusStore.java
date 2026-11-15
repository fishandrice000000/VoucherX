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
 * Redis 请求状态的辅助缓存；消费者在数据库提交后尽力更新，用于追踪和观测。
 * <p>
 * Lua 仅接收成功与失败终态写入，保护 SUCCESS 和 RELEASED。
 * 当前对外查询由 SeckillService 只读取 MySQL 请求流水，缓存不是最终成功依据。
 * Redis 写入失败不会回滚已提交的建单事务。
 */
@Service
public class SeckillRequestStatusStore {

    private static final Logger log = LoggerFactory.getLogger(SeckillRequestStatusStore.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> statusScript;

    public SeckillRequestStatusStore(StringRedisTemplate redisTemplate,
            @Qualifier("seckillRequestStatusScript") DefaultRedisScript<Long> statusScript) {
        this.redisTemplate = redisTemplate;
        this.statusScript = statusScript;
    }

    /**
     * 数据库建单提交后写成功缓存；不表示订单已付款或用户券已发放。
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

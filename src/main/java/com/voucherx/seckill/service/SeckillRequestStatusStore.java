package com.voucherx.seckill.service;

import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.SeckillProperties;
import com.voucherx.seckill.model.SeckillRequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀请求状态存储。Redis 只用于追踪与查询加速，最终业务结果以 MySQL 订单表为准。
 *
 * 状态更新通过 Lua 做条件转移：入口较晚的 PROCESSING/UNKNOWN 不能覆盖消费者已写入的
 * SUCCESS/FAILED/RELEASED 终态。
 */
@Service
public class SeckillRequestStatusStore {

    private static final Logger log = LoggerFactory.getLogger(SeckillRequestStatusStore.class);

    private final StringRedisTemplate redisTemplate;
    private final SeckillProperties properties;
    private final DefaultRedisScript<Long> statusScript;

    public SeckillRequestStatusStore(StringRedisTemplate redisTemplate,
            SeckillProperties properties,
            @Qualifier("seckillRequestStatusScript") DefaultRedisScript<Long> statusScript) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.statusScript = statusScript;
    }

    public void markProcessing(String requestId, String messageId) {
        update(requestId, SeckillRequestStatus.PROCESSING, messageId, null, null);
    }

    public void markUnknown(String requestId, String messageId, String reason) {
        update(requestId, SeckillRequestStatus.UNKNOWN, messageId, null, reason);
    }

    public void markSuccess(String requestId, String messageId, Long orderId) {
        update(requestId, SeckillRequestStatus.SUCCESS, messageId, orderId, null);
    }

    public void markFailed(String requestId, String messageId, String reason) {
        update(requestId, SeckillRequestStatus.FAILED, messageId, null, reason);
    }

    public Map<String, String> find(String requestId) {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(RedisKeys.seckillRequest(requestId));
            Map<String, String> result = new HashMap<>();
            raw.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
            return result;
        } catch (DataAccessException ex) {
            log.warn("读取秒杀请求状态失败，requestId={}", requestId, ex);
            return Map.of();
        }
    }

    private void update(String requestId, SeckillRequestStatus status, String messageId,
            Long orderId, String reason) {
        try {
            redisTemplate.execute(
                    statusScript,
                    List.of(RedisKeys.seckillRequest(requestId)),
                    status.name(),
                    messageId == null ? "" : messageId,
                    orderId == null ? "" : String.valueOf(orderId),
                    reason == null ? "" : reason,
                    String.valueOf(properties.requestStatusTtlSeconds()));
        } catch (DataAccessException ex) {
            // 状态缓存失败不能影响已经提交的数据库事务，查询会回源 MySQL。
            log.warn("写入秒杀请求状态失败，requestId={}, status={}", requestId, status, ex);
        }
    }
}

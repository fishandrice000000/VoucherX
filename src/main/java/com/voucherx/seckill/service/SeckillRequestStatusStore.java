package com.voucherx.seckill.service;

import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.SeckillProperties;
import com.voucherx.seckill.model.SeckillRequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀请求状态存储。Redis 只用于追踪与查询加速，最终业务结果以 MySQL 订单表为准。
 */
@Service
public class SeckillRequestStatusStore {

    private static final Logger log = LoggerFactory.getLogger(SeckillRequestStatusStore.class);

    private final StringRedisTemplate redisTemplate;
    private final SeckillProperties properties;

    public SeckillRequestStatusStore(StringRedisTemplate redisTemplate, SeckillProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
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
        String key = RedisKeys.seckillRequest(requestId);
        Map<String, String> fields = new HashMap<>();
        fields.put("status", status.name());
        if (messageId != null) {
            fields.put("messageId", messageId);
        }
        if (orderId != null) {
            fields.put("orderId", String.valueOf(orderId));
        }
        if (reason != null) {
            fields.put("reason", reason);
        }
        try {
            redisTemplate.opsForHash().putAll(key, fields);
            redisTemplate.expire(key, Duration.ofSeconds(properties.requestStatusTtlSeconds()));
        } catch (DataAccessException ex) {
            // 状态缓存失败不能影响已经提交的数据库事务，查询会回源 MySQL。
            log.warn("写入秒杀请求状态失败，requestId={}, status={}", requestId, status, ex);
        }
    }
}

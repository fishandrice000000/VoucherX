package com.voucherx.shop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.ShopCacheProperties;
import com.voucherx.shop.entity.Shop;
import com.voucherx.shop.mapper.ShopMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商户缓存读取。Cache Aside 的读写逻辑集中在这里，Controller 不感知缓存细节。
 */
@Service
public class ShopQueryService {

    private static final Logger log = LoggerFactory.getLogger(ShopQueryService.class);
    private static final String NULL_CACHE = "";

    private final ShopMapper shopMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ShopCacheProperties properties;
    private final DefaultRedisScript<Long> shopLockReleaseScript;

    public ShopQueryService(ShopMapper shopMapper,
                            StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            ShopCacheProperties properties,
                            @Qualifier("shopLockReleaseScript") DefaultRedisScript<Long> shopLockReleaseScript) {
        this.shopMapper = shopMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.shopLockReleaseScript = shopLockReleaseScript;
    }

    /**
     * 返回 null 表示商户不存在。空值缓存用于防止缓存穿透。
     */
    public Shop getShop(Long shopId) {
        String cacheKey = RedisKeys.shopCache(shopId);
        CacheLookup cached = readCache(cacheKey);
        if (cached.hit()) {
            return cached.shop();
        }

        String lockKey = RedisKeys.shopLock(shopId);
        String lockValue = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + properties.lockWaitMillis();

        while (System.currentTimeMillis() < deadline) {
            if (tryLock(lockKey, lockValue)) {
                try {
                    // 双重检查：可能在其他线程重建缓存期间已经写入。
                    CacheLookup doubleCheck = readCache(cacheKey);
                    if (doubleCheck.hit()) {
                        return doubleCheck.shop();
                    }
                    return loadFromDatabaseAndCache(shopId, cacheKey);
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }

            sleepBriefly();
            cached = readCache(cacheKey);
            if (cached.hit()) {
                return cached.shop();
            }
        }

        // 极端情况下锁等待超时，保留可用性直接读库；正常情况下热点请求不会走到这里。
        log.warn("商户缓存互斥重建等待超时，降级为数据库查询，shopId={}", shopId);
        return shopMapper.selectById(shopId);
    }

    private Shop loadFromDatabaseAndCache(Long shopId, String cacheKey) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            writeCache(cacheKey, NULL_CACHE, Duration.ofSeconds(properties.nullTtlSeconds()));
            return null;
        }
        try {
            writeCache(cacheKey, objectMapper.writeValueAsString(shop), shopTtl());
        } catch (JsonProcessingException ex) {
            log.warn("商户信息序列化失败，本次仅返回数据库结果，shopId={}", shopId, ex);
        }
        return shop;
    }

    private CacheLookup readCache(String cacheKey) {
        try {
            String value = redisTemplate.opsForValue().get(cacheKey);
            if (value == null) {
                return CacheLookup.miss();
            }
            if (value.isEmpty()) {
                return CacheLookup.emptyValue();
            }
            return CacheLookup.hit(objectMapper.readValue(value, Shop.class));
        } catch (DataAccessException ex) {
            log.warn("读取商户缓存失败，降级查询数据库", ex);
            return CacheLookup.miss();
        } catch (JsonProcessingException ex) {
            log.warn("商户缓存内容无法解析，删除损坏缓存并回源数据库", ex);
            safeDelete(cacheKey);
            return CacheLookup.miss();
        }
    }

    private void writeCache(String cacheKey, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttl);
        } catch (DataAccessException ex) {
            log.warn("写入商户缓存失败，忽略缓存写入", ex);
        }
    }

    private boolean tryLock(String lockKey, String lockValue) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, lockValue, Duration.ofSeconds(properties.lockTtlSeconds()));
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException ex) {
            log.warn("获取商户缓存锁失败", ex);
            return false;
        }
    }

    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(shopLockReleaseScript, List.of(lockKey), lockValue);
        } catch (DataAccessException ex) {
            log.warn("释放商户缓存锁失败，锁将依赖 TTL 过期", ex);
        }
    }

    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            log.warn("删除损坏缓存失败", ex);
        }
    }

    private Duration shopTtl() {
        long extra = properties.ttlRandomSeconds() <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.ttlRandomSeconds() + 1);
        return Duration.ofSeconds(properties.ttlSeconds() + extra);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private record CacheLookup(boolean hit, Shop shop) {
        static CacheLookup miss() {
            return new CacheLookup(false, null);
        }

        static CacheLookup emptyValue() {
            return new CacheLookup(true, null);
        }

        static CacheLookup hit(Shop shop) {
            return new CacheLookup(true, shop);
        }
    }
}

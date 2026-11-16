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
 * 查询商户详情：先读 Redis，未命中时以商户级互斥锁协调 MySQL 回源和回填。
 * <p>
 * 空值缓存缓解同一不存在 ID 的重复查询，随机 TTL 分散集中失效。
 * 锁竞争超时或 Redis 访问失败时允许数据库回源，以保持查询路径可用。
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
     * 先查缓存；未命中则尝试抢锁，持锁者二次检查后查库、回填缓存，并在 finally 中释放自己的锁。
     * 其他请求短暂等待并重新读缓存；等待超过上限后直接查库，本次不回填。
     *
     * @param shopId
     *                   待查询的商户 ID
     *
     * @return 商户详情；数据库不存在或命中空值缓存时为 {@code null}
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

    /**
     * 持有重建锁时查 MySQL 并回填：不存在写空字符串短缓存，存在写带随机有效期的 JSON。
     *
     * @param shopId
     *                     商户 ID
     * @param cacheKey
     *                     对应的 Redis 商户缓存键
     *
     * @return 数据库结果；缓存写入或序列化失败不影响已查到的结果
     */
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

    /**
     * 区分真正未命中、空值命中和商户 JSON 命中；Redis 读取失败按未命中处理，损坏 JSON 尝试删除。
     *
     * @param cacheKey
     *                     商户缓存键
     *
     * @return 含命中标志的结果；命中时商户仍可能为空
     */
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

    /**
     * 尽力写入缓存；Redis 故障只记录日志，保留数据库查询结果。
     *
     * @param cacheKey
     *                     商户缓存键
     * @param value
     *                     商户 JSON 或表示不存在的空字符串
     * @param ttl
     *                     缓存有效时长
     */
    private void writeCache(String cacheKey, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(cacheKey, value, ttl);
        } catch (DataAccessException ex) {
            log.warn("写入商户缓存失败，忽略缓存写入", ex);
        }
    }

    /**
     * 用 SET NX 和过期时间抢缓存重建锁；返回失败时由外层等待并重试。
     *
     * @param lockKey
     *                      商户级重建锁键
     * @param lockValue
     *                      本次请求的随机锁令牌
     *
     * @return 成功持锁时为 {@code true}；竞争失败或 Redis 异常时为 {@code false}
     */
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

    /**
     * 通过 Lua 比较令牌后删除锁，避免原锁过期后误删其他请求取得的新锁。
     *
     * @param lockKey
     *                      商户级重建锁键
     * @param lockValue
     *                      本次持锁令牌
     */
    private void releaseLock(String lockKey, String lockValue) {
        try {
            redisTemplate.execute(shopLockReleaseScript, List.of(lockKey), lockValue);
        } catch (DataAccessException ex) {
            log.warn("释放商户缓存锁失败，锁将依赖 TTL 过期", ex);
        }
    }

    /**
     * 尽力删除损坏的缓存内容，删除失败只记日志。
     *
     * @param key
     *                需要删除的缓存键
     */
    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (DataAccessException ex) {
            log.warn("删除损坏缓存失败", ex);
        }
    }

    /**
     * 在正常缓存基础有效期上增加随机秒数，分散批量缓存的失效时刻。
     *
     * @return 本次商户缓存的有效时长
     */
    private Duration shopTtl() {
        long extra = properties.ttlRandomSeconds() <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.ttlRandomSeconds() + 1);
        return Duration.ofSeconds(properties.ttlSeconds() + extra);
    }

    /**
     * 抢锁失败后暂停 30 毫秒，避免忙循环；被中断时保留线程中断标记。
     */
    private void sleepBriefly() {
        try {
            Thread.sleep(30);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 缓存读取结果；hit 区分“没有缓存”和“缓存明确表示商户不存在”。
     *
     * @param hit
     *                 是否命中了缓存（包括空值缓存）
     * @param shop
     *                 商户详情；未命中或空值命中时为空
     */
    private record CacheLookup(boolean hit, Shop shop) {
        /**
         * 构造未命中结果，外层需要尝试回源。
         *
         * @return hit 为 false 的结果
         */
        static CacheLookup miss() {
            return new CacheLookup(false, null);
        }

        /**
         * 构造空值命中结果，表示已确认商户不存在，无需立即查库。
         *
         * @return hit 为 true、shop 为空的结果
         */
        static CacheLookup emptyValue() {
            return new CacheLookup(true, null);
        }

        /**
         * 构造正常商户命中结果。
         *
         * @param shop
         *                 从缓存反序列化的商户
         *
         * @return hit 为 true 的结果
         */
        static CacheLookup hit(Shop shop) {
            return new CacheLookup(true, shop);
        }
    }
}

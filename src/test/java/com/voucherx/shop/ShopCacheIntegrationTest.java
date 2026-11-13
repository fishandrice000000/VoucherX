package com.voucherx.shop;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.shop.entity.Shop;
import com.voucherx.shop.mapper.ShopMapper;
import com.voucherx.shop.service.ShopQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ShopCacheIntegrationTest extends AbstractIntegrationTest {

    private static final long SHOP_ID = 100L;

    @Autowired
    private ShopQueryService shopQueryService;

    @SpyBean
    private ShopMapper shopMapper;

    @Test
    void cacheMissThenHit() {
        insertShop("缓存测试商户");
        Mockito.clearInvocations(shopMapper);

        Shop first = shopQueryService.getShop(SHOP_ID);
        Shop second = shopQueryService.getShop(SHOP_ID);

        assertThat(first.getName()).isEqualTo("缓存测试商户");
        assertThat(second.getName()).isEqualTo(first.getName());
        verify(shopMapper, times(1)).selectById(eq(SHOP_ID));

        String key = RedisKeys.shopCache(SHOP_ID);
        assertThat(redisTemplate.opsForValue().get(key)).contains("缓存测试商户");
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(1800L, 2100L);
    }

    @Test
    void emptyValueCachePreventsPenetration() {
        Mockito.clearInvocations(shopMapper);

        assertThat(shopQueryService.getShop(9999L)).isNull();
        assertThat(shopQueryService.getShop(9999L)).isNull();

        verify(shopMapper, times(1)).selectById(eq(9999L));
        String key = RedisKeys.shopCache(9999L);
        assertThat(redisTemplate.opsForValue().get(key)).isEmpty();
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(1L, 120L);
    }

    @Test
    void cacheExpiryReloadsLatestDatabaseValue() {
        insertShop("旧名称");
        Mockito.clearInvocations(shopMapper);

        assertThat(shopQueryService.getShop(SHOP_ID).getName()).isEqualTo("旧名称");

        jdbcTemplate.update("UPDATE tb_shop SET name = ? WHERE id = ?", "新名称", SHOP_ID);
        assertThat(shopQueryService.getShop(SHOP_ID).getName()).isEqualTo("旧名称");

        redisTemplate.delete(RedisKeys.shopCache(SHOP_ID));
        assertThat(shopQueryService.getShop(SHOP_ID).getName()).isEqualTo("新名称");
        verify(shopMapper, times(2)).selectById(eq(SHOP_ID));
    }

    @Test
    void concurrentMissRebuildsCacheOnceAndReleasesLock() throws Exception {
        insertShop("热点商户");
        Mockito.clearInvocations(shopMapper);

        int concurrency = 20;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Shop>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return shopQueryService.getShop(SHOP_ID);
                }));
            }
            start.countDown();
            for (Future<Shop> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).getName()).isEqualTo("热点商户");
            }
        } finally {
            executor.shutdownNow();
        }

        verify(shopMapper, times(1)).selectById(eq(SHOP_ID));
        assertThat(redisTemplate.hasKey(RedisKeys.shopLock(SHOP_ID))).isFalse();
    }

    private void insertShop(String name) {
        jdbcTemplate.update("""
                INSERT INTO tb_shop
                    (id, name, type_id, images, area, address, avg_price, sold, comments, score, open_hours)
                VALUES (?, ?, 1, '', '', '', 1000, 0, 0, 45, '10:00-22:00')
                """,
                SHOP_ID, name);
    }
}

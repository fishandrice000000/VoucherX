package com.voucherx.seckill.service;

import com.voucherx.common.redis.RedisKeys;
import com.voucherx.fulfillment.mapper.ActivityControlMapper;
import com.voucherx.fulfillment.mapper.SeckillRequestMapper;
import com.voucherx.order.mapper.PurchaseOrderEntityMapper;
import com.voucherx.voucher.entity.StockMode;
import com.voucherx.voucher.mapper.SeckillVoucherBucketMapper;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Map;

/**
 * 在活动控制行独占锁内，为满足安全条件的活动初始化 Redis 门禁和库存。
 * <p>
 * 先核对活动 NORMAL、当前代次及完整布局；完整 gate/stock 已存在时保留现状。
 * 库存缺失时，活动须没有订单或未结请求，且数据库额度仍为初始总量；随后先写完整 gate，再 SET NX 库存。
 * <p>
 * Redis 写入与数据库事务分别生效。仅写完 gate 的中断可在核对后重试；已有库存但门禁缺失、代次不符或活动冻结时拒绝初始化。
 */
@Component
@ConditionalOnProperty(name = "voucherx.maintenance-mode", havingValue = "false", matchIfMissing = true)
public class SeckillStockInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeckillStockInitializer.class);
    private final ActivityControlMapper controls;
    private final SeckillRequestMapper requests;
    private final SeckillVoucherMapper activities;
    private final SeckillVoucherBucketMapper buckets;
    private final PurchaseOrderEntityMapper orders;
    private final SeckillStockService stock;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;

    public SeckillStockInitializer(SeckillVoucherMapper activities, SeckillVoucherBucketMapper buckets,
            PurchaseOrderEntityMapper orders, SeckillStockService stock, ActivityControlMapper controls,
            SeckillRequestMapper requests, StringRedisTemplate redis, PlatformTransactionManager manager) {
        this.activities = activities;
        this.buckets = buckets;
        this.orders = orders;
        this.stock = stock;
        this.controls = controls;
        this.requests = requests;
        this.redis = redis;
        this.tx = new TransactionTemplate(manager);
    }

    /**
     * 启动后逐个活动执行受事务保护的初始化；单个活动异常不阻断其他活动。
     *
     * @param args
     *                 启动参数；此流程不读取其内容
     */
    @Override
    public void run(ApplicationArguments args) {
        for (var activity : activities.selectList(null)) {
            try {
                initializeSafely(activity.getVoucherId());
            } catch (Exception ex) {
                log.error("初始化秒杀 Redis 状态异常，voucherId={}", activity.getVoucherId(), ex);
            }
        }
    }

    /**
     * 确认活动存在，再在事务内独占锁定控制行并重新读取活动配置。
     * <p>
     * NORMAL 活动已有完整且同代次的 gate/stock 时直接保留；否则仅允许无订单、无未结请求且库存未消费的活动补建库存。
     * 缺失 gate 只允许与缺失库存一起初始化，禁止用数据库状态猜测已有 Redis 结构是否可信。
     * 故障导致只写入 gate 时，重试会核对它再继续 SET NX 库存，不重置已有量。
     *
     * @param voucherId
     *                      活动 ID
     * @return 状态完整或安全初始化成功为 true；配置、状态或基础设施不满足条件为 false
     */
    public boolean initializeSafely(Long voucherId) {
        if (activities.selectById(voucherId) == null)
            return false;
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                controls.insertIfAbsent(voucherId);
                var control = controls.selectByVoucherIdForUpdate(voucherId);
                if (control == null || !"NORMAL".equals(control.state()) || control.epoch() < 1)
                    return false;
                var activity = activities.selectById(voucherId);
                if (activity == null)
                    return false;
                StockMode mode;
                try {
                    mode = StockMode.requireLayout(activity);
                } catch (IllegalArgumentException ex) {
                    log.warn("库存布局不合法，拒绝初始化，voucherId={}", voucherId);
                    return false;
                }
                var gate = redis.opsForHash().entries(RedisKeys.seckillGate(voucherId));
                boolean stockExists = stock.exists(voucherId);
                if (gate.isEmpty()) {
                    if (stockExists)
                        return false;
                } else if (!"NORMAL".equals(gate.get("state"))
                        || !String.valueOf(control.epoch()).equals(gate.get("epoch")))
                    return false;
                if (stockExists)
                    return true;
                if (requests.countUnsettled(voucherId) > 0 || orders.countByVoucherId(voucherId) > 0)
                    return false;
                long remaining;
                if (mode == StockMode.SINGLE) {
                    if (!activity.getStock().equals(activity.getTotalStock()))
                        return false;
                    remaining = activity.getStock();
                } else {
                    int layout = activity.getLayoutVersion();
                    var stats = buckets.selectLayoutStats(voucherId, layout);
                    if (stats == null || !stats.covers(activity.getBucketCount())
                            || buckets.sumTotal(voucherId, layout) != activity.getTotalStock())
                        return false;
                    remaining = buckets.sumRemaining(voucherId, layout);
                    if (remaining != activity.getTotalStock())
                        return false;
                }
                if (remaining < 0 || remaining > Integer.MAX_VALUE)
                    return false;
                redis.opsForHash().putAll(RedisKeys.seckillGate(voucherId),
                        Map.of("state", "NORMAL", "epoch", String.valueOf(control.epoch())));
                return stock.initializeIfAbsent(voucherId, (int) remaining);
            }));
        } catch (DataAccessException ex) {
            log.warn("初始化活动状态失败，保留现状待重试，voucherId={}", voucherId, ex);
            return false;
        }
    }
}

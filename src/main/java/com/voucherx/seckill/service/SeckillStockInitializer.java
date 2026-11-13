package com.voucherx.seckill.service;

import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.voucher.entity.SeckillVoucher;
import com.voucherx.voucher.entity.StockMode;
import com.voucherx.voucher.mapper.SeckillVoucherBucketMapper;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时只对“可以安全确定状态”的活动做 Redis 库存初始化。
 * 已有历史订单但 Redis 库存丢失时拒绝自动重建，避免按原始总库存重置导致超发。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "voucherx.maintenance-mode", havingValue = "false", matchIfMissing = true)
public class SeckillStockInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockInitializer.class);

    private final com.voucherx.fulfillment.mapper.ActivityControlMapper controls;
    private final com.voucherx.fulfillment.mapper.SeckillRequestMapper requests;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final SeckillVoucherBucketMapper bucketMapper;
    private final VoucherOrderMapper orderMapper;
    private final SeckillStockService stockService;

    public SeckillStockInitializer(SeckillVoucherMapper seckillVoucherMapper,
            SeckillVoucherBucketMapper bucketMapper,
            VoucherOrderMapper orderMapper,
            SeckillStockService stockService, com.voucherx.fulfillment.mapper.ActivityControlMapper controls,
            com.voucherx.fulfillment.mapper.SeckillRequestMapper requests) {
        this.controls = controls;
        this.requests = requests;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.bucketMapper = bucketMapper;
        this.orderMapper = orderMapper;
        this.stockService = stockService;
    }

    /**
     * 启动后逐个检查活动并尝试安全初始化；单个活动失败记日志，不阻断其他活动检查。
     *
     * @param args
     *                 Spring Boot 启动参数；本流程不读取其内容
     */
    @Override
    public void run(ApplicationArguments args) {
        List<SeckillVoucher> activities;
        try {
            activities = seckillVoucherMapper.selectList(null);
        } catch (Exception ex) {
            log.error("启动时读取秒杀活动失败，跳过 Redis 库存初始化", ex);
            return;
        }

        for (SeckillVoucher activity : activities) {
            try {
                initializeIfSafe(activity);
            } catch (Exception ex) {
                log.error("初始化秒杀 Redis 库存异常，voucherId={}", activity.getVoucherId(), ex);
            }
        }
    }

    /**
     * 按活动 ID 加载配置后执行安全初始化；用于显式初始化或测试准备。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return Redis 已存在或本次成功初始化为 true；活动不存在、条件不满足或写入失败为 false
     */
    public boolean initializeSafely(Long voucherId) {
        SeckillVoucher activity = seckillVoucherMapper.selectById(voucherId);
        if (activity == null) {
            return false;
        }
        return initializeIfSafe(activity);
    }

    /**
     * 先保证活动控制行存在；Redis 库存已存在就直接保留。
     * <p>
     * 库存缺失时，必须没有未结请求、没有非正常控制状态、没有历史订单。
     * SINGLE 还要求当前库存等于总库存；BUCKET 要求桶连续覆盖且总量、剩余量均等于初始总量。
     * 不能证明是未消费初始状态时拒绝自动重建，交给专门恢复流程处理。
     *
     * @param activity
     *                     从 MySQL 读取的活动配置
     *
     * @return 可以保留现有库存或安全创建 Redis 库存时为 true
     */
    private boolean initializeIfSafe(SeckillVoucher activity) {
        Long voucherId = activity.getVoucherId();
        controls.insertIfAbsent(voucherId);
        if (stockService.exists(voucherId)) {
            return true;
        }

        Long pending = requests.countUnsettled(voucherId);
        Long frozen = controls.countFrozen(voucherId);
        if (pending != null && pending > 0 || frozen != null && frozen > 0)
            return false;
        long existingOrders = orderMapper.countByVoucherId(voucherId);
        if (existingOrders != 0) {
            log.warn("Redis 秒杀库存缺失且已有历史订单，拒绝自动重建，voucherId={}, orders={}",
                    voucherId, existingOrders);
            return false;
        }

        StockMode mode = StockMode.parse(activity.getStockMode());
        int layoutVersion = activity.getLayoutVersion() == null ? 1 : activity.getLayoutVersion();

        if (mode == StockMode.SINGLE) {
            boolean untouched = activity.getStock().equals(activity.getTotalStock());
            if (!untouched) {
                log.warn("Redis 单库存缺失且数据库库存已被改动，拒绝自动重建，voucherId={}, stock={}, totalStock={}",
                        voucherId, activity.getStock(), activity.getTotalStock());
                return false;
            }
            boolean initialized = stockService.initializeIfAbsent(voucherId, activity.getStock());
            if (initialized) {
                log.info("已初始化秒杀 Redis 单库存，voucherId={}, stock={}",
                        voucherId, activity.getStock());
            }
            return initialized;
        }

        int bucketCount = activity.getBucketCount() == null ? 0 : activity.getBucketCount();
        var layout = bucketMapper.selectLayoutStats(voucherId, layoutVersion);
        if (bucketCount <= 0 || layout == null || !layout.covers(bucketCount)) {
            log.warn("Redis 库存缺失且 MySQL 桶编号未连续覆盖，拒绝自动重建，voucherId={}, bucketCount={}, layout={}, stats={}",
                    voucherId, bucketCount, layoutVersion, layout == null ? "null"
                            : layout.getBucketCount() + "/" + layout.getMinBucketNo()
                                    + "/" + layout.getMaxBucketNo());
            return false;
        }
        long bucketTotal = bucketMapper.sumTotal(voucherId, layoutVersion);
        long bucketRemaining = bucketMapper.sumRemaining(voucherId, layoutVersion);
        if (bucketTotal != activity.getTotalStock() || bucketRemaining != activity.getTotalStock()) {
            log.warn(
                    "Redis 库存缺失且 MySQL 桶不满足未消费条件，拒绝自动重建，voucherId={}, totalStock={}, bucketTotal={}, bucketRemaining={}",
                    voucherId, activity.getTotalStock(), bucketTotal, bucketRemaining);
            return false;
        }
        if (bucketRemaining > Integer.MAX_VALUE) {
            log.warn("MySQL 桶库存超出 Redis 初始化支持范围，voucherId={}", voucherId);
            return false;
        }
        boolean initialized = stockService.initializeIfAbsent(voucherId, (int) bucketRemaining);
        if (initialized) {
            log.info("已初始化秒杀 Redis 库存（MySQL 桶模式），voucherId={}, stock={}, buckets={}",
                    voucherId, bucketRemaining, bucketCount);
        }
        return initialized;
    }
}

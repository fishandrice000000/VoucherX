package com.voucherx.seckill.service;

import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.voucher.entity.SeckillVoucher;
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
public class SeckillStockInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockInitializer.class);

    private final SeckillVoucherMapper seckillVoucherMapper;
    private final VoucherOrderMapper orderMapper;
    private final SeckillStockService stockService;

    public SeckillStockInitializer(SeckillVoucherMapper seckillVoucherMapper,
                                   VoucherOrderMapper orderMapper,
                                   SeckillStockService stockService) {
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.orderMapper = orderMapper;
        this.stockService = stockService;
    }

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

    private void initializeIfSafe(SeckillVoucher activity) {
        Long voucherId = activity.getVoucherId();
        if (stockService.exists(voucherId)) {
            return;
        }

        long existingOrders = orderMapper.countByVoucherId(voucherId);
        boolean noHistory = existingOrders == 0;
        boolean untouchedStock = activity.getStock().equals(activity.getTotalStock());
        if (!noHistory || !untouchedStock) {
            log.warn("Redis 秒杀库存缺失且无法安全重建，活动暂停受理，voucherId={}, stock={}, totalStock={}, orders={}",
                    voucherId, activity.getStock(), activity.getTotalStock(), existingOrders);
            return;
        }

        if (stockService.initializeIfAbsent(voucherId, activity.getStock())) {
            log.info("已初始化秒杀 Redis 库存，voucherId={}, stock={}", voucherId, activity.getStock());
        }
    }
}

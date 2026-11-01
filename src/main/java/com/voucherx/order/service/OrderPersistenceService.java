package com.voucherx.order.service;

import com.voucherx.config.SeckillProperties;
import com.voucherx.config.TransactionDelay;
import com.voucherx.order.entity.VoucherOrder;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.voucher.entity.SeckillVoucher;
import com.voucherx.voucher.entity.StockMode;
import com.voucherx.voucher.mapper.SeckillVoucherBucketMapper;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀订单的 MySQL 事务边界。
 *
 * SINGLE 模式：条件扣减活动库存行。
 * BUCKET 模式：按消息起始桶开始，有界尝试后续桶，只扣实际成功的那一行。
 * 桶扣减与订单插入必须属于同一个事务；任何一步失败都回滚。
 */
@Service
public class OrderPersistenceService {

    private final VoucherOrderMapper orderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final SeckillVoucherBucketMapper bucketMapper;
    private final SeckillProperties properties;
    private final TransactionDelay transactionDelay;

    public OrderPersistenceService(VoucherOrderMapper orderMapper,
                                   SeckillVoucherMapper seckillVoucherMapper,
                                   SeckillVoucherBucketMapper bucketMapper,
                                   SeckillProperties properties,
                                   TransactionDelay transactionDelay) {
        this.orderMapper = orderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.bucketMapper = bucketMapper;
        this.properties = properties;
        this.transactionDelay = transactionDelay;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderPersistResult persist(SeckillOrderMessage message) {
        // 正常重复消费：同一个 requestId 已经成功落库。
        VoucherOrder existing = orderMapper.selectByRequestId(message.requestId());
        if (existing != null) {
            return new OrderPersistResult(existing.getId(), false);
        }

        // 数据库组合唯一约束是最终一人一单保障；这里提前检查，减少无意义的库存扣减。
        VoucherOrder existingUserOrder =
                orderMapper.selectByVoucherAndUser(message.voucherId(), message.userId());
        if (existingUserOrder != null) {
            throw new OrderBusinessException(OrderFailure.DUPLICATE_USER,
                    "该用户已存在同一活动订单");
        }

        SeckillVoucher activity = seckillVoucherMapper.selectById(message.voucherId());
        if (activity == null) {
            throw new OrderBusinessException(OrderFailure.VOUCHER_NOT_FOUND,
                    "秒杀活动不存在");
        }

        StockMode activityMode = StockMode.parse(activity.getStockMode());
        int layoutVersion = activity.getLayoutVersion() == null ? 1 : activity.getLayoutVersion();
        validateMessageLayout(message, activityMode, layoutVersion);

        Integer actualBucket = activityMode == StockMode.BUCKET
                ? deductBucket(message, activity, layoutVersion)
                : deductSingleStock(message, activity);

        // 仅实验环境：在事务内、扣库存之后注入延迟，模拟数据库处理能力下降并
        // 放大真实锁/提交窗口；默认配置下为空操作。
        transactionDelay.apply();

        VoucherOrder order = new VoucherOrder();
        order.setRequestId(message.requestId());
        order.setMessageId(message.messageId());
        order.setUserId(message.userId());
        order.setVoucherId(message.voucherId());
        order.setStockMode(activityMode.name());
        order.setBucketNo(actualBucket);
        order.setLayoutVersion(layoutVersion);
        order.setStatus(VoucherOrder.STATUS_CREATED);
        // 事务提交会稍晚于该时间，作为端到端观测的落库近似点。
        order.setCompletedAtMs(System.currentTimeMillis());
        // 唯一约束冲突会抛出 DuplicateKeyException，使整个事务（含库存扣减）回滚。
        orderMapper.insert(order);
        return new OrderPersistResult(order.getId(), true);
    }

    private Integer deductSingleStock(SeckillOrderMessage message, SeckillVoucher activity) {
        int changed = seckillVoucherMapper.deductStock(message.voucherId());
        if (changed == 0) {
            throw new OrderBusinessException(OrderFailure.SOLD_OUT, "数据库库存不足");
        }
        return 0;
    }

    private Integer deductBucket(SeckillOrderMessage message, SeckillVoucher activity,
                                 int layoutVersion) {
        int bucketCount = activity.getBucketCount() == null ? 0 : activity.getBucketCount();
        if (bucketCount <= 0) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "库存桶数量不合法");
        }
        var layout = bucketMapper.selectLayoutStats(message.voucherId(), layoutVersion);
        if (layout == null || !layout.covers(bucketCount)) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "库存桶编号未连续覆盖 0..bucketCount-1");
        }

        int startBucket = message.startBucketNo() == null
                ? Math.floorMod(message.userId(), bucketCount)
                : message.startBucketNo();
        if (startBucket < 0 || startBucket >= bucketCount) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "消息中的库存桶不属于该活动布局");
        }

        int attempts = Math.min(Math.max(1, properties.bucketAttempts()), bucketCount);
        for (int i = 0; i < attempts; i++) {
            int bucketNo = (startBucket + i) % bucketCount;
            if (bucketMapper.deductBucket(message.voucherId(), bucketNo, layoutVersion) == 1) {
                return bucketNo;
            }
        }

        long remaining = bucketMapper.sumRemaining(message.voucherId(), layoutVersion);
        if (remaining == 0) {
            throw new OrderBusinessException(OrderFailure.SOLD_OUT, "数据库库存不足");
        }
        throw new OrderBusinessException(OrderFailure.LOCAL_SOLD_OUT,
                "有界桶尝试耗尽，仍有其他桶库存");
    }

    private void validateMessageLayout(SeckillOrderMessage message, StockMode activityMode,
                                       int layoutVersion) {
        String messageMode = message.stockMode();
        if (activityMode == StockMode.SINGLE) {
            if (messageMode != null && !messageMode.isBlank()
                    && !StockMode.SINGLE.name().equalsIgnoreCase(messageMode)) {
                throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                        "消息库存模式与活动不一致");
            }
            int messageLayout = message.layoutVersion() == null ? 0 : message.layoutVersion();
            if (messageLayout != 0 && messageLayout != layoutVersion) {
                throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                        "消息布局版本与单库存活动不一致");
            }
            return;
        }

        // BUCKET 活动必须携带明确布局；旧 SINGLE 消息不能被解释为桶请求。
        if (messageMode == null || !StockMode.BUCKET.name().equalsIgnoreCase(messageMode)) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "BUCKET 活动收到缺少库存模式的消息");
        }
        if (message.layoutVersion() == null || message.layoutVersion() != layoutVersion) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "消息布局版本与桶活动不一致");
        }
    }
}

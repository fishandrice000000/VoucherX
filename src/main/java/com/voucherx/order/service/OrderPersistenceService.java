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
 * 异步建单的 MySQL 事务边界：校验受理依据、条件扣库存、插购买订单、固定权益快照、更新请求成功。
 * <p>
 * 这些操作共用同一事务；库存不足、唯一约束冲突或后续异常均回滚。
 * SINGLE 扣活动库存行，BUCKET 从消息起始桶开始有界寻找可扣减桶。
 * <p>
 * 正常经 Spring 代理返回仅表示购买订单创建完成，付款发券继续阅读
 * {@link com.voucherx.commerce.CommerceService#simulate}。
 */
@Service
public class OrderPersistenceService {

    private final com.voucherx.recovery.FaultInjector faults;
    private final com.voucherx.fulfillment.RequestLedger ledger;
    private final com.voucherx.commerce.CommerceService commerce;
    private final VoucherOrderMapper orderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final SeckillVoucherBucketMapper bucketMapper;
    private final SeckillProperties properties;
    private final TransactionDelay transactionDelay;

    public OrderPersistenceService(VoucherOrderMapper orderMapper,
            SeckillVoucherMapper seckillVoucherMapper,
            SeckillVoucherBucketMapper bucketMapper,
            SeckillProperties properties,
            TransactionDelay transactionDelay, com.voucherx.fulfillment.RequestLedger ledger,
            com.voucherx.commerce.CommerceService commerce, com.voucherx.recovery.FaultInjector faults) {
        this.faults = faults;
        this.commerce = commerce;
        this.ledger = ledger;
        this.orderMapper = orderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.bucketMapper = bucketMapper;
        this.properties = properties;
        this.transactionDelay = transactionDelay;
    }

    /**
     * 先通过账本守卫持有活动共享锁和请求行锁，再检查同 requestId 是否已有订单。
     * <p>
     * 重复投递返回原订单，不重复扣库存；新请求检查一人一单、活动和布局后条件扣 MySQL 库存，
     * 插入订单并回填自增 ID，初始化 PENDING_PAYMENT 及权益快照，最后把请求更新为 SUCCEEDED。
     * 该方法没有发送 MQ 或操作 Redis；代理事务提交后消费者才能 ACK。
     *
     * @param message
     *                    经解析且将与账本进一步核对的订单消息
     *
     * @return 订单 ID 及是否本次新建；经事务代理正常返回时事务已提交
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderPersistResult persist(SeckillOrderMessage message) {
        ledger.guard(message);
        // 正常重复消费：同一个 requestId 已经成功落库。
        VoucherOrder existing = orderMapper.selectByRequestId(message.requestId());
        if (existing != null) {
            return new OrderPersistResult(existing.getId(), false);
        }

        // 数据库组合唯一约束是最终一人一单保障；这里提前检查，减少无意义的库存扣减。
        VoucherOrder existingUserOrder = orderMapper.selectByVoucherAndUser(message.voucherId(), message.userId());
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
        commerce.initializePurchase(order.getId(), message.voucherId());
        ledger.succeed(message.requestId(), order.getId());
        faults.hit(com.voucherx.recovery.FaultPoint.ORDER_BEFORE_COMMIT, message.requestId(), message.messageId());
        return new OrderPersistResult(order.getId(), true);
    }

    /**
     * 在一个外层事务内顺序调用 persist；内部调用共用当前事务，任一失败则整批回滚。
     * <p>
     * 不是为每条另开事务，也不是 JDBC 批执行；消费者在整体回滚后再逐条降级。
     *
     * @param messages
     *                     消费者已限制数量和总字节数的非空批次
     *
     * @return 与输入顺序一致的订单结果列表
     */
    @Transactional(rollbackFor = Exception.class)
    public java.util.List<OrderPersistResult> persistBatch(java.util.List<SeckillOrderMessage> messages) {
        // 有界批次共用一个事务；任一失败由调用方在回滚后拆分为逐条处理。
        var results = messages.stream().map(this::persist).toList();
        faults.hit(com.voucherx.recovery.FaultPoint.BATCH_BEFORE_COMMIT, messages.getFirst().requestId(),
                messages.getFirst().messageId());
        return results;
    }

    /**
     * 通过 stock 大于 0 的条件更新扣减一份 MySQL 单库存；受影响行数为 0 则报售罄。
     *
     * @param message
     *                     用于定位活动的业务消息
     * @param activity
     *                     已读取的活动配置
     *
     * @return SINGLE 模式记录的桶号 0
     */
    private Integer deductSingleStock(SeckillOrderMessage message, SeckillVoucher activity) {
        int changed = seckillVoucherMapper.deductStock(message.voucherId());
        if (changed == 0) {
            throw new OrderBusinessException(OrderFailure.SOLD_OUT, "数据库库存不足");
        }
        return 0;
    }

    /**
     * 先验证桶布局完整，再从消息起始桶循环尝试配置允许的有限个桶，返回实际扣减成功的桶。
     * <p>
     * 尝试耗尽后汇总剩余库存：总量为 0 是 SOLD_OUT，其他桶还有库存则是 LOCAL_SOLD_OUT。
     *
     * @param message
     *                          包含计划起始桶的消息
     * @param activity
     *                          提供桶数量的活动配置
     * @param layoutVersion
     *                          已核验的布局版本
     *
     * @return 实际成功扣减的桶号，可能不同于计划起始桶
     */
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

    /**
     * 核验消息库存模式及布局版本；SINGLE 兼容缺少布局的旧消息，BUCKET 必须显式匹配。
     *
     * @param message
     *                          消息中的模式及布局信息
     * @param activityMode
     *                          数据库当前活动模式
     * @param layoutVersion
     *                          数据库当前布局版本
     */
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

package com.voucherx.order.service;

import com.voucherx.config.SeckillProperties;
import com.voucherx.config.TransactionDelay;
import com.voucherx.order.entity.PurchaseOrderEntity;
import com.voucherx.order.mapper.PurchaseOrderEntityMapper;
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
 * {@link com.voucherx.commerce.CommerceService#simulatePayment}。
 */
@Service
public class PurchaseOrderCreationService {

    private final com.voucherx.recovery.FaultInjector faults;
    private final com.voucherx.fulfillment.RequestLedger ledger;
    private final com.voucherx.commerce.CommerceService commerce;
    private final PurchaseOrderEntityMapper orderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;
    private final SeckillVoucherBucketMapper bucketMapper;
    private final SeckillProperties properties;
    private final TransactionDelay transactionDelay;

    public PurchaseOrderCreationService(PurchaseOrderEntityMapper orderMapper,
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
     * 在一个 MySQL 事务内校验受理依据、扣减库存并创建购买订单。
     * <p>
     * 先取得活动共享锁和请求行锁，核对消息身份；同请求已有订单时返回其 ID，created=false。
     * 首次建单校验用户资格与当前布局，条件扣减 SINGLE 或 BUCKET 库存，插入订单、固定权益快照并将请求记为 SUCCEEDED。
     * <p>
     * 这些写入共同提交或回滚。completedAtMs
     * 是插单前记录的观测时刻；独立消费者调用的事务代理返回后完成提交，createPurchaseOrdersInBatch
     * 内调用则共享批事务。
     * 提交后的购买订单为 PENDING_PAYMENT，消费者继续更新结果快照并确认消息。
     *
     * @param message
     *                    身份将由账本守卫再次核对的 MQ 业务消息
     * @return 本次新建或复用的订单 ID 和 created 标志；是否已提交取决于外层事务边界
     * @see com.voucherx.fulfillment.RequestLedger#lockAndValidateForOrderCreation
     * @see com.voucherx.commerce.CommerceService#initializePurchase
     * @see #createPurchaseOrdersInBatch
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseOrderCreationResult createPurchaseOrder(SeckillOrderMessage message) {
        ledger.lockAndValidateForOrderCreation(message);
        // 正常重复消费：同一个 requestId 已经成功落库。
        PurchaseOrderEntity existing = orderMapper.selectByRequestId(message.requestId());
        if (existing != null) {
            return new PurchaseOrderCreationResult(existing.getId(), false);
        }

        // 数据库组合唯一约束是最终一人一单保障；这里提前检查，减少无意义的库存扣减。
        PurchaseOrderEntity existingUserOrder = orderMapper.selectByVoucherAndUser(message.voucherId(),
                message.userId());
        if (existingUserOrder != null) {
            throw new OrderBusinessException(OrderFailure.DUPLICATE_USER,
                    "该用户已存在同一活动订单");
        }

        SeckillVoucher activity = seckillVoucherMapper.selectById(message.voucherId());
        if (activity == null) {
            throw new OrderBusinessException(OrderFailure.VOUCHER_NOT_FOUND,
                    "秒杀活动不存在");
        }

        StockMode activityMode;
        try {
            activityMode = StockMode.requireLayout(activity);
        } catch (IllegalArgumentException ex) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH, "活动库存布局不合法");
        }
        int layoutVersion = activity.getLayoutVersion();
        validateMessageLayout(message, activityMode, layoutVersion);

        Integer actualBucket = activityMode == StockMode.BUCKET
                ? deductBucket(message, activity, layoutVersion)
                : deductSingleStock(message, activity);

        // 仅实验环境：在事务内、扣库存之后注入延迟，模拟数据库处理能力下降并
        // 放大真实锁/提交窗口；默认配置下为空操作。
        transactionDelay.apply();

        PurchaseOrderEntity order = new PurchaseOrderEntity();
        order.setRequestId(message.requestId());
        order.setMessageId(message.messageId());
        order.setUserId(message.userId());
        order.setVoucherId(message.voucherId());
        order.setStockMode(activityMode.name());
        order.setBucketNo(actualBucket);
        order.setLayoutVersion(layoutVersion);
        order.setStatus(PurchaseOrderEntity.STATUS_CREATED);
        // 事务提交会稍晚于该时间，作为端到端观测的落库近似点。
        order.setCompletedAtMs(System.currentTimeMillis());
        // 唯一约束冲突会抛出 DuplicateKeyException，使整个事务（含库存扣减）回滚。
        orderMapper.insert(order);
        commerce.initializePurchase(order.getId(), message.voucherId());
        ledger.markOrderCreated(message.requestId(), order.getId());
        faults.hit(com.voucherx.recovery.FaultPoint.ORDER_BEFORE_COMMIT, message.requestId(), message.messageId());
        return new PurchaseOrderCreationResult(order.getId(), true);
    }

    /**
     * 通过 Spring 代理开启批事务，按消息顺序调用 createPurchaseOrder，共同使用该事务的连接和锁。
     * <p>
     * 业务 SQL 逐项执行；任一项异常会回滚整批库存、订单及请求结果。
     * 代理成功返回后，消费者逐条确认消息；批事务回滚后，消费者按单条事务处理每项结果。
     *
     * @param messages
     *                     消费者已限制大小的非空消息批次
     * @return 与输入顺序一致的建单结果；正常消费调用在外层代理提交后才取得此列表
     * @see com.voucherx.mq.consumer.SeckillOrderConsumer
     */
    @Transactional(rollbackFor = Exception.class)
    public java.util.List<PurchaseOrderCreationResult> createPurchaseOrdersInBatch(
            java.util.List<SeckillOrderMessage> messages) {
        // 有界批次共用一个事务；任一失败由调用方在回滚后拆分为逐条处理。
        var results = messages.stream().map(this::createPurchaseOrder).toList();
        faults.hit(com.voucherx.recovery.FaultPoint.BATCH_BEFORE_COMMIT, messages.getFirst().requestId(),
                messages.getFirst().messageId());
        return results;
    }

    /**
     * 使用 stock 大于 0 的条件 UPDATE 扣减一份单库存。
     * <p>
     * 受影响行数为 0 时抛 SOLD_OUT；扣减与订单插入共用建单事务，后续步骤失败时一起回滚。
     * Redis 预留的释放由持久化关闭后的补偿协议执行。
     *
     * @param message
     *                     用于定位活动的业务消息
     * @param activity
     *                     已读取的活动配置；本方法实际只使用消息中的活动 ID
     * @return SINGLE 订单记录的桶号 0
     * @throws OrderBusinessException
     *                                    没有扣到库存
     * @see SeckillVoucherMapper#deductStock(Long)
     */
    private Integer deductSingleStock(SeckillOrderMessage message, SeckillVoucher activity) {
        int changed = seckillVoucherMapper.deductStock(message.voucherId());
        if (changed == 0) {
            throw new OrderBusinessException(OrderFailure.SOLD_OUT, "数据库库存不足");
        }
        return 0;
    }

    /**
     * 在建单事务内，从消息中的计划起点有界尝试数据库库存桶。
     * <ol>
     * <li>校验桶数、桶号覆盖及消息起点范围。</li>
     * <li>候选次数为 min(max(1, bucketAttempts), bucketCount)，配置默认值为 3。</li>
     * <li>依次访问 (startBucket+i)%bucketCount，要求布局匹配且 remaining_stock 大于
     * 0，命中后立即返回桶号。</li>
     * </ol>
     * <p>
     * 例如起点 1、桶数 4、预算 3，候选为 1、2、3。候选均失败后汇总全活动余量：零余量抛 SOLD_OUT，仍有余量抛 LOCAL_SOLD_OUT。
     * 实际命中桶随购买订单保存，同事务提交。
     *
     * @param message
     *                          含用户、活动和计划起始桶的消息
     * @param activity
     *                          提供当前桶数量的活动配置
     * @param layoutVersion
     *                          已核验的活动布局版本
     * @return 实际扣减成功的桶号，用于保存订单实际扣减位置
     * @throws OrderBusinessException
     *                                    布局不合法、全部售罄或本次候选局部售罄
     * @see com.voucherx.voucher.model.BucketLayoutStats#covers(int)
     * @see SeckillVoucherBucketMapper#deductBucket
     */
    private Integer deductBucket(SeckillOrderMessage message, SeckillVoucher activity,
            int layoutVersion) {
        int bucketCount = activity.getBucketCount();
        if (bucketCount <= 0) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "库存桶数量不合法");
        }
        var layout = bucketMapper.selectLayoutStats(message.voucherId(), layoutVersion);
        if (layout == null || !layout.covers(bucketCount)) {
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH,
                    "库存桶编号未连续覆盖 0..bucketCount-1");
        }

        int startBucket = message.startBucketNo();
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
     * 校验消息明确携带库存模式、正布局版本和有效起始桶，并与当前活动配置匹配。
     * <p>
     * 账本守卫已先核对持久化身份；此处再确认消息与当前活动配置一致。
     * SINGLE 的起点只能为 0，BUCKET 的起点范围和完整桶布局另在扣减时校验。
     *
     * @param message
     *                          本次业务消息
     * @param activityMode
     *                          已校验的活动库存模式
     * @param layoutVersion
     *                          活动布局版本
     * @throws OrderBusinessException
     *                                    消息字段缺失或与活动布局不匹配
     */
    private void validateMessageLayout(SeckillOrderMessage message, StockMode activityMode, int layoutVersion) {
        if (!activityMode.name().equals(message.stockMode())
                || message.layoutVersion() == null || message.layoutVersion() != layoutVersion
                || message.startBucketNo() == null || message.startBucketNo() < 0
                || activityMode == StockMode.SINGLE && message.startBucketNo() != 0)
            throw new OrderBusinessException(OrderFailure.LAYOUT_MISMATCH, "消息库存布局与活动不一致");
    }
}

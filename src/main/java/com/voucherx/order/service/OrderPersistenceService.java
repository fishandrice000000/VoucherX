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
     * 03 的建单事务主体：先守卫并持有活动/请求锁，再按请求 ID 查询已有订单。
     * <p>
     * 同请求已成功则返回原 orderId、created=false；新请求检查一人一单及活动布局，条件扣 MySQL 库存，
     * 插入购买订单并回填 ID，初始化付款规则快照，最后把请求写为 SUCCEEDED。这些写入共同提交或回滚。
     * <p>
     * 订单的 completedAtMs 在插入前设置，只是观测近似点，不能据此判断已经提交。
     * 调用者为消费者且没有外层事务时，代理正常返回即已提交；在 persistBatch 中的内部调用则仍处于未提交批事务。
     * <p>
     * 这里不操作 Redis、不 ACK；提交后仍是 PENDING_PAYMENT，后续付款发券另见 CommerceService。
     *
     * @param message
     *                    身份将由账本守卫再次核对的 MQ 业务消息
     * @return 本次新建或复用的订单 ID 和 created 标志；是否已提交取决于外层事务边界
     * @see com.voucherx.fulfillment.RequestLedger#guard
     * @see com.voucherx.commerce.CommerceService#initializePurchase
     * @see #persistBatch
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
     * 通过 Spring 代理开启一个外层事务，再按消息顺序内部调用 this.persist；内部调用不再穿过新的事务代理。
     * <p>
     * 每项都使用现有事务和同一组事务资源，SQL 仍逐条执行，不是 JDBC batch。
     * 例如第二项失败，第一项本批扣库存/插订单/改请求的写入也回滚，方法内部第一项返回不代表已提交。
     * <p>
     * 外层代理正常返回后，消费者才逐条 ACK；代理回滚后才可进入一次逐条降级。
     *
     * @param messages
     *                     消费者已限制大小的非空消息批次
     * @return 与输入顺序一致的建单结果；正常消费调用在外层代理提交后才取得此列表
     * @see com.voucherx.mq.consumer.SeckillOrderConsumer
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
     * 在建单事务中用 stock 大于 0 的条件 SQL 扣一份 SINGLE 库存；更新行数为 0 则抛 SOLD_OUT。
     * <p>
     * 即使多个线程曾读到 stock=1，真正 UPDATE 仍以执行时的条件竞争，不会把该行扣为负数。
     * 插单及后续步骤失败会让本次 MySQL 扣减回滚；它不会顺带返还已预留的 Redis 额度。
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
     * 在调用者的建单事务内，从计划起点开始有界尝试 MySQL 库存桶。
     * <ol>
     * <li>要求桶数量为正，聚合统计结合主键约束确认编号覆盖 0 到 bucketCount-1。</li>
     * <li>优先取消息起点；为空时按用户取模，再检查起点范围。</li>
     * <li>尝试次数为 min(max(1, bucketAttempts), bucketCount)，默认最多 3 个候选。</li>
     * <li>第 i 次访问 (startBucket+i)%bucketCount；只在剩余量大于 0、布局匹配时扣 1，命中立即返回。</li>
     * </ol>
     * <p>
     * 例如用户 1001、4 桶，起点 1 已空而桶 2 有 25 份：桶 1 更新 0 行，桶 2 更新 1 行并剩 24，
     * 返回 2。订单 bucket_no 保存 2，消息 startBucketNo 仍为 1；二者不能混为同一个字段。
     * <p>
     * 候选全部失败后才汇总余额：观察到总量 0 抛 SOLD_OUT，仍有余额抛 LOCAL_SOLD_OUT。
     * 例如 8 桶、起点 5、最多 3 次，只查 5/6/7，即使桶 0 有额度也不会继续无界搜索。
     * 该汇总用于本次分支判断，不是冻结所有桶后取得的全局不变快照。
     * <p>
     * 成功扣桶仍需与插单、权益快照和请求成功状态一起提交；后续异常会回滚此扣减，Redis 预留另按补偿协议处理。
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
     * 要求消息显式声明当前模式、正布局版本及有效起始桶，不补齐缺失字段。
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

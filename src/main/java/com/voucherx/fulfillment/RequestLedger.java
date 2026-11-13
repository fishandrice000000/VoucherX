package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.OrderFailure;
import com.voucherx.fulfillment.mapper.SeckillRequestMapper;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

/**
 * 抢券请求的 MySQL 账本，记录从意图到受理、建单或关闭的业务阶段。
 * <p>
 * 主线先调用 {@link #intent}，Redis 预留成功后调用 {@link #accept}；消费者在建单事务内调用
 * {@link #guard} 和 {@link #succeed}。受理与 Outbox 事件写入同一个数据库事务，消息发送发生在事务之外。
 * <p>
 * 统一锁顺序为活动控制行共享锁 → 请求行锁 → 后续库存行锁；Redis 和 MQ 不参加这些 MySQL 事务。
 */
@Service
public class RequestLedger {
    private final SeckillRequestMapper requests;
    private final OutboxMapper outbox;
    private final ActivityGate gate;
    private final Clock clock;
    private final DeliveryProperties config;

    public RequestLedger(SeckillRequestMapper requests, OutboxMapper outbox, ActivityGate gate, Clock clock,
            DeliveryProperties config) {
        this.requests = requests;
        this.outbox = outbox;
        this.gate = gate;
        this.clock = clock;
        this.config = config;
    }

    /**
     * 普通查询请求流水，不加行锁；查询本身不校验请求属于哪位用户。
     *
     * @param id
     *               请求 ID
     * @return 流水快照；不存在时为 {@code null}
     */
    public RequestRecord find(String id) {
        return requests.find(id);
    }

    /**
     * 查找同一用户此前使用该幂等键创建的请求，供 HTTP 重试复用。
     *
     * @param userId
     *                   已认证用户 ID
     * @param key
     *                   客户端幂等键或服务端生成的键
     * @return 已有流水；没有命中时为 {@code null}
     */
    public RequestRecord byKey(long userId, String key) {
        return requests.byKey(userId, key);
    }

    /**
     * 确认活动 NORMAL 并锁住控制行，取当前 epoch，再为新请求生成 requestId 和固定 eventId，插入 INTENT/UNKNOWN。
     * <p>
     * 此时只有可核对的意图，没有 Outbox 或订单，Redis 还没预扣。
     * 主线 SeckillService 没有外层事务，经本方法 Spring 事务代理正常返回后意图已提交，再继续 Redis。
     * <p>
     * 注解使用默认 REQUIRED；若调用方已有事务则加入它，提交取决于外层结束，不能把所有方法返回都当作独立提交。
     * 例如批量测试夹具会在同一外层事务中调用 intent 与 accept。
     *
     * @param user
     *                    已认证用户 ID
     * @param voucher
     *                    活动 ID
     * @param key
     *                    用户下唯一的客户端幂等键或本次生成的键
     * @param mode
     *                    SINGLE 或 BUCKET
     * @param bucket
     *                    计划起始桶，SINGLE 为 0
     * @param layout
     *                    本次绑定的库存布局版本
     * @return 刚插入的意图快照，提交时刻受当前事务传播关系决定
     * @see com.voucherx.seckill.service.SeckillService#seckill(Long, Long, String)
     * @see #accept(String)
     */
    @Transactional
    public RequestRecord intent(long user, long voucher, String key, String mode, int bucket, int layout) {
        long epoch = gate.requireNormal(voucher);
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        requests.insertIntent(id, UUID.randomUUID().toString(), user, voucher, key, epoch, mode, bucket, layout, now);
        return find(id);
    }

    /**
     * 通过 FOR UPDATE 读取请求并持有行锁；不存在则报错。
     * <p>
     * 调用者须已有数据库事务，并先取得活动控制锁；否则无法让锁覆盖后续业务操作。
     *
     * @param id
     *               要保护的请求 ID
     * @return 加锁后的当前流水
     */
    public RequestRecord lock(String id) {
        RequestRecord record = requests.lock(id);
        if (record == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        return record;
    }

    /**
     * 在同一个 MySQL 事务中确认受理依据并留下待发事件，接续已成功的 Redis 预留。
     * <ol>
     * <li>普通读出请求所属活动，再取得 NORMAL 活动共享锁。</li>
     * <li>FOR UPDATE 锁请求，核对它的 epoch 等于活动当前代次。</li>
     * <li>只有 INTENT 才写 ACCEPTED/RESERVED，并用固定 eventId 插入默认 PENDING 的 Outbox。</li>
     * </ol>
     * <p>
     * 状态更新与事件插入一起提交或回滚；非 INTENT 不重复写事件，直接返回当前流水，并不强制把其他阶段改回受理。
     * 主线没有外层事务，代理正常返回才表示这次受理提交完成；已有外层事务时按 REQUIRED 加入。
     * <p>
     * 本方法不验证 Redis 归属证据本身，调用者须已确认预留成功；这里不再扣 Redis、不发送 MQ，也不创建订单。
     *
     * @param id
     *               已有 Redis 预留成功依据的请求 ID
     * @return 受理后或重复调用时的当前流水快照
     * @see OutboxDispatcher#dispatch()
     * @see com.voucherx.fulfillment.mapper.SeckillRequestMapper#accept
     */
    @Transactional
    public RequestRecord accept(String id) {
        RequestRecord before = find(id);
        if (before == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        long epoch = gate.requireNormal(before.voucherId());
        RequestRecord r = lock(id);
        if (r.epoch() != epoch)
            throw new BusinessException(ErrorCode.CONFLICT, "请求恢复代次已失效");
        if ("INTENT".equals(r.phase())) {
            requests.accept(id, clock.millis());
            outbox.insert(r.eventId(), id, clock.millis());
        }
        return find(id);
    }

    /**
     * 在扣库存、插单的同一事务内，按活动共享锁 → 请求行锁保护受理依据。
     * <p>
     * 先核对消息和流水身份；找不到流水或身份不符抛 INVALID_REQUEST。
     * 身份匹配后，活动非 NORMAL 或代次不符抛 StaleDeliveryException；请求 CLOSED 则抛 REQUEST_CLOSED。
     * 只允许 ACCEPTED 或 SUCCEEDED，其他阶段也停止本次投递。
     * <p>
     * 保留 SUCCEEDED 是为了同请求重投能继续查询原订单，而不是再次扣库存。
     * 本方法自己没有事务注解，调用者须让这些锁覆盖后续库存和订单操作，直到一起提交或回滚。
     *
     * @param message
     *                    已解析且必需字段完整的 MQ 消息
     * @return 被加锁并通过身份、活动及阶段检查的请求
     * @see com.voucherx.order.service.OrderPersistenceService#persist
     * @see RequestRecord#matches
     */
    public RequestRecord guard(SeckillOrderMessage message) {
        var c = gate.lock(message.voucherId());
        if (find(message.requestId()) == null)
            throw new OrderBusinessException(OrderFailure.INVALID_REQUEST, "无持久化受理依据");
        RequestRecord r = lock(message.requestId());
        if (!r.matches(message))
            throw new OrderBusinessException(OrderFailure.INVALID_REQUEST, "消息身份/布局/代次不匹配");
        if (!"NORMAL".equals(c.state()) || c.epoch() != message.epoch())
            throw new StaleDeliveryException();
        if ("CLOSED".equals(r.phase()))
            throw new OrderBusinessException(OrderFailure.REQUEST_CLOSED, "请求已经关闭");
        if (!"ACCEPTED".equals(r.phase()) && !"SUCCEEDED".equals(r.phase()))
            throw new StaleDeliveryException();
        return r;
    }

    /**
     * 在建单调用者的现有事务内，只把 ACCEPTED 更新为 SUCCEEDED 并关联订单。
     * <p>
     * 与条件扣库存、插购买订单和固定付款规则快照一起提交。reservation 不在这里改成 RELEASED，
     * 它仍可记录原预留事实，不能因此再把成功占用重复统计成未结工作。
     * 本方法不单独开启事务，也不发送 ACK；消费者要等订单服务事务代理返回。
     *
     * @param id
     *                    已通过守卫的请求 ID
     * @param orderId
     *                    刚插入并回填的购买订单 ID
     */
    public void succeed(String id, long orderId) {
        requests.succeed(id, orderId, clock.millis());
    }

    /**
     * 仅在 Redis 明确返回拒绝结果时，将仍为 INTENT 的请求关闭为 CLOSED/NONE，并保存原因。
     * <p>
     * 先锁活动再锁请求，数据库修改在本方法事务中提交。因为已明确没有预留，本方法不增加 Redis 库存。
     * Redis 超时或结果未知时不能走此分支，应保留意图供核对。
     *
     * @param id
     *                   已存在且 Redis 明确未预留成功的请求 ID
     * @param reason
     *                   明确拒绝原因，例如 RESERVE_-5 表示售罄
     */
    @Transactional
    public void rejectUnreserved(String id, String reason) {
        RequestRecord before = find(id);
        gate.lock(before.voucherId());
        RequestRecord r = lock(id);
        if ("INTENT".equals(r.phase()))
            requests.rejectUnreserved(id, reason, clock.millis());
    }

    /**
     * 在已经回滚的建单调用之外，核对活动、代次及消息身份，处理确定业务拒绝。
     * <p>
     * 身份不符、活动异常、代次不符或已 SUCCEEDED 时返回 false；只有仍为 ACCEPTED 才写 CLOSED。
     * 其他通过检查的非成功阶段可直接返回 true，因此返回值表示允许消费者确认此拒绝，不等于本次一定改过数据库。
     * <p>
     * 消费者仅在 true 时写失败缓存并 ACK；false 时 NACK，不覆盖真实请求结果。
     * 关闭事实不自动增加 Redis 库存，预留释放另走补偿屏障。
     *
     * @param message
     *                    被判定为业务拒绝的投递消息
     * @param reason
     *                    业务分类名称等关闭原因
     * @return 是否允许消费者确认此业务拒绝；不是 SQL 更新行数
     * @see com.voucherx.mq.consumer.SeckillOrderConsumer
     */
    @Transactional
    public boolean fail(SeckillOrderMessage message, String reason) {
        var c = gate.lock(message.voucherId());
        if (find(message.requestId()) == null)
            return false;
        RequestRecord r = lock(message.requestId());
        if (!r.matches(message) || !"NORMAL".equals(c.state()) || c.epoch() != r.epoch())
            return false;
        if ("SUCCEEDED".equals(r.phase()))
            return false;
        if ("ACCEPTED".equals(r.phase()))
            requests.close(r.requestId(), reason, clock.millis());
        return true;
    }

    /**
     * 在单独的服务调用事务中，为暂时性建单异常重新安排原事件。
     * <p>
     * 按活动与请求锁检查身份、非终态、正常状态和 epoch，然后按已有 attempts 写 PENDING 或次数用尽后的 QUARANTINED，
     * 下次时间为当前时刻加固定 retryMillis，并清空发送认领令牌。
     * 请求保留受理和预留，eventId 不变；不是生成新的业务请求，也不使用旧的 TTL 重试队列。
     * <p>
     * 消费者等此调用正常返回后才 NACK 原交付。检查不满足会直接返回，不能单凭 void 返回就认定必定产生了新重试写入。
     *
     * @param message
     *                    原投递消息
     * @param reason
     *                    异常类型等可记录诊断原因
     * @see com.voucherx.fulfillment.mapper.OutboxMapper#defer
     */
    @Transactional
    public void defer(SeckillOrderMessage message, String reason) {
        var c = gate.lock(message.voucherId());
        RequestRecord r = lock(message.requestId());
        if (!r.matches(message) || r.terminal() || c.epoch() != r.epoch() || !"NORMAL".equals(c.state()))
            return;
        outbox.defer(r.requestId(), config.maxAttempts(), clock.millis() + config.retryMillis(), reason);
    }

    /**
     * 记录请求诊断原因，并把对应事件隔离，停止自动投递。
     * <p>
     * 本方法没有独立事务注解；两条写入是否同事务取决于调用方，不能假定自动原子提交。
     *
     * @param id
     *                   请求 ID
     * @param reason
     *                   隔离原因
     */
    public void quarantine(String id, String reason) {
        requests.recordReason(id, reason, clock.millis());
        outbox.quarantine(id, reason);
    }
}

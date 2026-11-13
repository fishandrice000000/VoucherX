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
     *
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
     *
     * @return 已有流水；没有命中时为 {@code null}
     */
    public RequestRecord byKey(long userId, String key) {
        return requests.byKey(userId, key);
    }

    /**
     * 确认活动 NORMAL，取得当前恢复代次，再生成请求 ID 和事件 ID 插入意图。
     * <p>
     * 初始 INTENT/UNKNOWN 来自表默认值；这一步尚未执行 Redis 预扣，也没有创建 Outbox 或订单。
     * 经 Spring 事务代理正常返回后，后续 Redis 操作即使失败，意图记录仍可用于核对恢复。
     * 抢券主线没有外层事务，因此此处单独提交一次；若其他调用方已有事务，则按默认 REQUIRED 传播加入该事务。
     *
     * @param user
     *                    当前用户 ID
     * @param voucher
     *                    活动 ID
     * @param key
     *                    同一用户下唯一的幂等键
     * @param mode
     *                    SINGLE 或 BUCKET 库存模式
     * @param bucket
     *                    计划从哪个库存桶开始；SINGLE 为 0
     * @param layout
     *                    活动当前布局版本
     *
     * @return 新插入的请求流水
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
     *
     * @return 加锁后的当前流水
     */
    public RequestRecord lock(String id) {
        RequestRecord record = requests.lock(id);
        if (record == null)
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        return record;
    }

    /**
     * 在一个 MySQL 事务里把 INTENT 改为 ACCEPTED/RESERVED，并插入 PENDING 的 Outbox 事件。
     * <p>
     * 先要求活动 NORMAL，再锁请求并核对恢复代次。只有 INTENT 阶段执行写入，重复受理不会重复插入事件。
     * 调用前应已有 Redis 预留成功证据；本方法不会再次扣 Redis、发送 MQ 消息或创建订单。
     * <p>
     * 经过 Spring 事务代理正常返回时，两项数据库写入一起提交；任一步失败则一起回滚。
     *
     * @param id
     *               Redis 已成功预留的请求 ID
     *
     * @return 处理后的流水快照
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
     * 在建单事务内校验消息是否仍有有效的持久化受理依据，并持有活动共享锁及请求行锁。
     * <p>
     * 逐项比较请求、事件、用户、活动、恢复代次和库存布局，只允许 ACCEPTED 或 SUCCEEDED。
     * 身份匹配后，若活动代次已变化或活动冻结，则抛 StaleDeliveryException，由消费者停止本次旧投递。
     * 本方法自身不开事务，锁必须覆盖调用者接下来的扣库存和插订单。
     *
     * @param message
     *                    从 MQ 反序列化的业务消息
     *
     * @return 已加锁且身份匹配的请求流水
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
     * 在调用者的建单事务内将 ACCEPTED 流水改为 SUCCEEDED 并关联购买订单 ID。
     * <p>
     * 与扣 MySQL 库存、插购买订单及金额快照一起提交；表示建单完成，后续付款和发券仍未发生。
     *
     * @param id
     *                    消息中的请求 ID
     * @param orderId
     *                    本次插入并回填的购买订单 ID
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
     * 消费者遇到确定的业务拒绝后，验证消息身份、活动状态及代次，再关闭仍为 ACCEPTED 的请求。
     * <p>
     * 已成功请求不会被覆盖；身份不匹配的消息不能关闭真实请求。
     * 这里只写数据库关闭事实，Redis 预留的释放由后续补偿链路核对处理。
     *
     * @param message
     *                    失败的投递消息
     * @param reason
     *                    可记录的业务失败原因
     *
     * @return true 表示通过校验且不是成功请求，消费者可确认该业务拒绝；false 表示不能据此确认关闭
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
     * 遇到暂时性异常时，在验证身份和代次后把 Outbox 安排为待重试，达到次数上限则隔离。
     * <p>
     * 业务仍保留受理和预留，不把发布失败或处理异常直接等同于业务失败。
     *
     * @param message
     *                    需重试的消息
     * @param reason
     *                    异常类型等诊断原因
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

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
     *                   该用户提交的客户端幂等键
     * @return 已有流水；没有命中时为 {@code null}
     */
    public RequestRecord byKey(long userId, String key) {
        return requests.byKey(userId, key);
    }

    /**
     * 在活动 NORMAL 的共享锁保护下创建请求意图，生成 requestId 和固定 eventId，保存 INTENT/UNKNOWN。
     * <p>
     * SeckillService 通过事务代理调用：意图提交后继续 Redis 预留，再进入可靠受理。
     * 事务传播为 REQUIRED；独立调用在代理返回时提交，已有外层事务时加入该事务，提交由最外层完成。
     *
     * @param user
     *                    已认证用户 ID
     * @param voucher
     *                    活动 ID
     * @param key
     *                    同一用户下唯一并绑定活动的客户端幂等键
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
     * 在同一个 MySQL 事务中，将已获得 Redis 预留的请求记为可靠受理并插入待发事件。
     * <p>
     * 先读取所属活动并取得 NORMAL 共享锁，再 FOR UPDATE 锁请求并核对代次。
     * INTENT 更新为 ACCEPTED/RESERVED，固定 eventId 写入 PENDING Outbox；其他阶段返回当前账本快照。
     * 状态和事件共同提交或回滚，调用者负责先确认 Redis 预留成功。
     * 事务采用 REQUIRED，提交时刻由当前最外层事务决定。
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
     * 在建单事务内依次取得活动共享锁与请求行锁，校验消息身份和请求执行条件。
     * <p>
     * 身份缺失或不符抛 INVALID_REQUEST；身份匹配后，冻结、代次失效或阶段尚未就绪抛 StaleDeliveryException。
     * CLOSED 抛 REQUEST_CLOSED；ACCEPTED 可首次建单，SUCCEEDED 可复用已有订单。
     * 调用者保持这些锁，直至库存、订单和请求结果共同提交或回滚。
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
     * 在调用者建单事务中，将 ACCEPTED 记为 SUCCEEDED 并关联购买订单 ID。
     * <p>
     * 该修改与库存扣减、订单及权益快照共同提交；reservation 保留预留事实。
     * 消费者在事务代理成功返回后继续同步结果和 ACK。
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
     * 将 Redis 明确拒绝的 INTENT 记为 CLOSED/NONE，并保存拒绝原因。
     * <p>
     * 按活动、请求顺序持锁后提交关闭结果；结果未知的请求保留意图，由后续查证处理。
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
     * 在建单事务回滚后核对消息与当前账本，决定是否接受本次业务拒绝。
     * <p>
     * 身份、活动、代次须有效，且请求尚未成功；满足条件的 ACCEPTED 更新为 CLOSED，其他已符合拒绝条件的阶段可直接返回 true。
     * 消费者据 true 写失败快照并 ACK，据 false 隔离交付。预留返还由补偿协议接续。
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
     * 在活动和请求锁保护下，为暂时性建单异常重新安排同一 Outbox 事件。
     * <p>
     * 校验身份、活动 NORMAL、当前代次及非终态；按累计 attempts 写 PENDING 或 QUARANTINED，设置固定 retryMillis
     * 等待并清空令牌。
     * 原请求、预留和 eventId 保持关联。校验不满足时直接返回，消费者按该调用的实际执行路径结束本次交付。
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
     * 记录请求诊断原因，并将对应事件隔离以停止自动投递。
     * <p>
     * 请求与事件的两次更新使用调用者提供的事务边界。
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

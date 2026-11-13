package com.voucherx.seckill.service;

import com.voucherx.admission.SeckillAdmissionService;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.RequestLedger;
import com.voucherx.fulfillment.RequestRecord;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.dto.SeckillRequestStatusResponse;
import com.voucherx.seckill.model.SeckillRequestStatus;
import com.voucherx.voucher.entity.StockMode;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 抢券主线编排：前置校验 → MySQL 意图 → Redis 预留 → MySQL 受理和 Outbox。
 * <p>
 * 本类没有包住全流程的数据库事务；intent 与 accept 分别经过 RequestLedger 的 Spring 事务代理，
 * Redis 操作在两次提交之间。这样留下可恢复的证据，也意味着中间可能停在 UNKNOWN 状态。
 * <p>
 * 不是每次 HTTP 尝试都会新增流水：前置校验失败不会写意图，同一用户复用幂等键会返回原请求。
 * 正常受理后继续阅读 {@link com.voucherx.fulfillment.OutboxDispatcher#dispatch}。
 */
@Service
public class SeckillService {
    private final SeckillVoucherMapper activities;
    private final VoucherOrderMapper orders;
    private final SeckillStockService stock;
    private final RequestLedger ledger;
    private final com.voucherx.recovery.FaultInjector faults;
    private final SeckillAdmissionService admission;
    private final Clock clock;

    public SeckillService(SeckillVoucherMapper activities, VoucherOrderMapper orders, SeckillStockService stock,
            RequestLedger ledger, SeckillAdmissionService admission, Clock clock,
            com.voucherx.recovery.FaultInjector faults) {
        this.faults = faults;
        this.activities = activities;
        this.orders = orders;
        this.stock = stock;
        this.ledger = ledger;
        this.admission = admission;
        this.clock = clock;
    }

    /**
     * 未提供客户端幂等键的调用入口；每次新尝试由服务端生成独立键。
     *
     * @param user
     *                    已认证用户 ID
     * @param voucher
     *                    活动 ID
     *
     * @return 请求 ID 及当前处理状态
     */
    public SeckillAcceptResponse seckill(Long user, Long voucher) {
        return seckill(user, voucher, null);
    }

    /**
     * 执行一次抢券编排，先处理幂等键重放，再校验活动时间、库存布局并进行全局准入。
     * <p>
     * 通过校验后，intent 先提交 INTENT/UNKNOWN 流水，再调用 Redis Lua。
     * 明确的负数返回会关闭未预留意图并抛业务错误；Redis 调用结果不确定时保留意图并返回 UNKNOWN，
     * 不能据此盲目释放库存。非负返回（包括剩余库存 0）表示预留成功，随后 accept 原子提交受理和待发事件。
     * <p>
     * accept 经事务代理完成提交后，本方法返回 PROCESSING；此时已持久化 ACCEPTED 和 Outbox，订单仍由消费者异步创建。
     *
     * @param user
     *                      已认证用户 ID；不是客户端任意指定的身份
     * @param voucher
     *                      URL 指定的活动 ID
     * @param clientKey
     *                      可选客户端幂等键；同一用户下唯一并绑定活动
     *
     * @return 新请求的 PROCESSING/UNKNOWN，或同键原请求的当前状态
     */
    public SeckillAcceptResponse seckill(Long user, Long voucher, String clientKey) {
        if (clientKey != null && !clientKey.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "Idempotency-Key 格式不合法");
        if (clientKey != null) {
            RequestRecord old = ledger.byKey(user, clientKey);
            if (old != null)
                return replay(old, voucher);
        }
        var activity = activities.selectById(voucher);
        if (activity == null)
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        long now = clock.millis();
        long begin = activity.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = activity.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (now < begin)
            throw new BusinessException(ErrorCode.SECKILL_NOT_STARTED);
        if (now > end)
            throw new BusinessException(ErrorCode.SECKILL_ENDED);
        StockMode mode = StockMode.parse(activity.getStockMode());
        int buckets = activity.getBucketCount() == null ? 1 : activity.getBucketCount();
        if (buckets < 1)
            throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
        int bucket = mode == StockMode.BUCKET ? Math.floorMod(user, buckets) : 0;
        admission.acquireGlobal(voucher);
        String key = clientKey == null ? UUID.randomUUID().toString() : clientKey;
        RequestRecord r;
        try {
            r = ledger.intent(user, voucher, key, mode.name(), bucket,
                    activity.getLayoutVersion() == null ? 1 : activity.getLayoutVersion());
        } catch (DuplicateKeyException ex) {
            r = ledger.byKey(user, key);
            if (r == null)
                throw ex;
            return replay(r, voucher);
        }
        long result;
        try {
            result = stock.reserve(voucher, user, r.requestId(), now, begin, end, mode.name(), bucket,
                    r.layoutVersion(), r.epoch());
        } catch (BusinessException ex) {
            // 预扣调用结果可能未知。意图已持久化，返回可查询 ID，由扫描核对，而非盲目释放。
            return new SeckillAcceptResponse(r.requestId(), SeckillRequestStatus.UNKNOWN);
        }
        if (result < 0) {
            ledger.rejectUnreserved(r.requestId(), "RESERVE_" + result);
            throw new BusinessException(switch ((int) result) {
                case -2 -> ErrorCode.SECKILL_NOT_STARTED;
                case -3 -> ErrorCode.SECKILL_ENDED;
                case -4 -> ErrorCode.SECKILL_DUPLICATE;
                case -5 -> ErrorCode.SECKILL_SOLD_OUT;
                default -> ErrorCode.SECKILL_STOCK_UNAVAILABLE;
            });
        }
        // 发生异常时保留 INTENT 和 Redis 归属证据，扫描可重新完成受理。
        faults.hit(com.voucherx.recovery.FaultPoint.RESERVE_DONE, r.requestId(), r.eventId());
        ledger.accept(r.requestId());
        faults.hit(com.voucherx.recovery.FaultPoint.ACCEPT_COMMITTED, r.requestId(), r.eventId());
        return new SeckillAcceptResponse(r.requestId(), SeckillRequestStatus.PROCESSING);
    }

    /**
     * 复用已有请求，不再次预扣 Redis；同一幂等键绑定其他活动时拒绝。
     *
     * @param r
     *                    按用户及幂等键找到的原流水
     * @param voucher
     *                    本次请求的活动 ID
     *
     * @return 原请求 ID 及当前业务状态
     */
    private SeckillAcceptResponse replay(RequestRecord r, long voucher) {
        if (r.voucherId() != voucher)
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已绑定其他活动");
        return new SeckillAcceptResponse(r.requestId(), status(r));
    }

    /**
     * 把数据库受理阶段转换为对外状态；SUCCESS 只表示购买订单已创建，不表示付款成功或已发券。
     *
     * @param r
     *              持久化请求流水
     *
     * @return SUCCEEDED→SUCCESS，CLOSED→FAILED，ACCEPTED→PROCESSING，其余→UNKNOWN
     */
    private SeckillRequestStatus status(RequestRecord r) {
        return switch (r.phase()) {
            case "SUCCEEDED" -> SeckillRequestStatus.SUCCESS;
            case "CLOSED" -> SeckillRequestStatus.FAILED;
            case "ACCEPTED" -> SeckillRequestStatus.PROCESSING;
            default -> SeckillRequestStatus.UNKNOWN;
        };
    }

    /**
     * 先读取 MySQL 流水并验证用户归属；只有没有流水时，才兼容查询历史购买订单。
     * <p>
     * 该接口不以 Redis 状态缓存作为最终事实；SUCCESS 返回的 orderId 还需要进入购买付款流程。
     *
     * @param user
     *                 当前登录用户 ID
     * @param id
     *                 客户端保存的 requestId
     *
     * @return 请求状态、可选购买订单 ID、事件 ID 及原因
     */
    public SeckillRequestStatusResponse queryRequest(Long user, String id) {
        RequestRecord r = ledger.find(id);
        if (r != null) {
            if (r.userId() != user)
                throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
            return new SeckillRequestStatusResponse(id, status(r), r.orderId(), r.eventId(), r.reason());
        }
        var order = orders.selectByRequestId(id);
        if (order == null || !user.equals(order.getUserId()))
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        return new SeckillRequestStatusResponse(id, SeckillRequestStatus.SUCCESS, order.getId(), order.getMessageId(),
                null);
    }
}

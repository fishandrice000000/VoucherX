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
     * @return 请求 ID 及当前处理状态
     */
    public SeckillAcceptResponse seckill(Long user, Long voucher) {
        return seckill(user, voucher, null);
    }

    /**
     * 执行抢券编排：幂等重放 → 活动校验 → 全局准入 → 持久化意图 → Redis 预留 → 可靠受理。
     * <p>
     * SINGLE 的起始桶固定为 0；BUCKET 使用 floorMod(user, bucketCount)，例如用户 1001、4 桶得到 1。
     * 此时只决定候选起点，不访问任何 MySQL 桶库存；实际扣减桶稍后由订单事务决定。
     * <p>
     * intent 先提交 INTENT/UNKNOWN，再向 Lua 传用户、活动、请求、当前/起止毫秒时间、模式、起始桶、布局和代次。
     * 明确负数结果关闭未预留意图并抛业务错误；Redis 调用异常保留意图并返回 UNKNOWN，不能凭异常释放。
     * 非负结果（包括 0）表示预留成功，此时 MySQL 库存还没扣。
     * <p>
     * accept 经事务代理提交 ACCEPTED 和 Outbox 后，本方法返回 PROCESSING，后台再异步建单。
     * 前置校验拒绝不新增意图，同用户同幂等键重放复用原请求，不再次预扣。
     *
     * @param user
     *                      已认证用户 ID
     * @param voucher
     *                      活动 ID
     * @param clientKey
     *                      可选客户端幂等键；同一用户下唯一并绑定活动
     * @return 新请求的 PROCESSING/UNKNOWN，或同键原请求的当前状态
     * @see com.voucherx.seckill.service.SeckillStockService#reserve(Long, Long,
     *      String, long, long, long, String, int, int, long)
     * @see com.voucherx.order.service.OrderPersistenceService#persist
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
     * 返回同用户同幂等键原请求的 ID 和当前阶段映射，不重新创建意图或预留。
     * <p>
     * 请求已绑定其他活动时抛 409 冲突；稳定键要由客户端在同一次业务重试中保持一致。
     * 这是业务幂等，不表示跳过 Controller 之前的认证和本地准入。
     *
     * @param r
     *                    根据 (user_id, client_key) 找到的原流水
     * @param voucher
     *                    本次希望抢的活动 ID
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
     * 先读 MySQL 请求账本并验证用户所有权，按业务阶段给出建单进度；无新账本时才兼容读取历史订单。
     * <p>
     * INTENT→UNKNOWN、ACCEPTED→PROCESSING、CLOSED→FAILED、SUCCEEDED→SUCCESS。
     * 不根据 Outbox 的 SENT 或 QUARANTINED 推断终态，也不以 Redis 缓存作为最终事实。
     * SUCCESS 的 orderId 是购买单，付款和发券仍是后续流程。
     *
     * @param user
     *                 当前登录用户 ID
     * @param id
     *                 客户端保存的真实 requestId
     * @return 带可选 orderId、固定事件 ID 和诊断原因的请求状态
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

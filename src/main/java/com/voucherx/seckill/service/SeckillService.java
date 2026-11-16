package com.voucherx.seckill.service;

import com.voucherx.admission.SeckillAdmissionService;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.RequestLedger;
import com.voucherx.fulfillment.RequestRecord;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.dto.SeckillRequestStatusResponse;
import com.voucherx.seckill.model.SeckillRequestStatus;
import com.voucherx.voucher.entity.StockMode;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.ZoneId;

/**
 * 抢券业务编排：活动校验、保存请求意图、Redis 预留、提交受理与 Outbox。
 * <p>
 * intent 和 accept 各自通过 RequestLedger 的事务代理执行，Redis 预留位于两次数据库提交之间。
 * 预留结果未知时保留意图，供客户端查询及对账查证；同用户同幂等键复用已有请求。
 * 受理提交后由 {@link com.voucherx.fulfillment.OutboxDispatcher#dispatch} 接续异步投递。
 */
@Service
public class SeckillService {
    private final SeckillVoucherMapper activities;
    private final SeckillStockService stock;
    private final RequestLedger ledger;
    private final com.voucherx.recovery.FaultInjector faults;
    private final SeckillAdmissionService admission;
    private final Clock clock;

    public SeckillService(SeckillVoucherMapper activities, SeckillStockService stock,
            RequestLedger ledger, SeckillAdmissionService admission, Clock clock,
            com.voucherx.recovery.FaultInjector faults) {
        this.faults = faults;
        this.activities = activities;
        this.stock = stock;
        this.ledger = ledger;
        this.admission = admission;
        this.clock = clock;
    }

    /**
     * 按幂等键处理一次抢券请求，并返回可查询的请求标识与建单状态。
     * <p>
     * 先查询同用户同幂等键的请求；首次执行时校验活动时间和布局，经过全局准入后提交 INTENT/UNKNOWN 意图。
     * SINGLE 起始桶为 0；BUCKET 按 floorMod(user, bucketCount) 选择起点，例如用户 1001、4 桶得到 1。
     * <p>
     * 预留 Lua 接收用户、活动、请求、时间、布局及代次：非负结果表示预留成功，负数结果触发请求关闭和业务拒绝。
     * Redis 调用结果未知时返回 UNKNOWN，并保留持久化意图用于查证。
     * 预留成功后，accept 同事务提交 ACCEPTED/RESERVED 和待发事件，返回 PROCESSING；消费者随后执行 MySQL 建单事务。
     *
     * @param user
     *                      已认证用户 ID
     * @param voucher
     *                      活动 ID
     * @param clientKey
     *                      必填客户端幂等键；同一用户下唯一并绑定活动
     * @return 新请求的 PROCESSING/UNKNOWN，或同键原请求的当前状态
     * @see com.voucherx.seckill.service.SeckillStockService#reserve(Long, Long,
     *      String, long, long, long, String, int, int, long)
     * @see com.voucherx.order.service.OrderPersistenceService#persist
     */
    public SeckillAcceptResponse seckill(Long user, Long voucher, String clientKey) {
        if (clientKey == null || !clientKey.matches("[A-Za-z0-9._:-]{1,64}"))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "Idempotency-Key 格式不合法");
        RequestRecord old = ledger.byKey(user, clientKey);
        if (old != null)
            return replay(old, voucher);
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
        StockMode mode;
        try {
            mode = StockMode.requireLayout(activity);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE, "库存布局配置不合法", ex);
        }
        int buckets = activity.getBucketCount();
        int bucket = mode == StockMode.BUCKET ? Math.floorMod(user, buckets) : 0;
        admission.acquireGlobal(voucher);
        RequestRecord r;
        try {
            r = ledger.intent(user, voucher, clientKey, mode.name(), bucket,
                    activity.getLayoutVersion());
        } catch (DuplicateKeyException ex) {
            r = ledger.byKey(user, clientKey);
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
     * 返回同用户同幂等键对应请求的标识和当前状态。
     * <p>
     * 请求绑定的活动与本次活动必须一致，否则抛出冲突；客户端重试同一次操作时保持幂等键稳定。
     * HTTP 请求先经过认证和本地准入，再进入此服务。
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
     * 将账本业务阶段映射为对外建单状态；SUCCESS 对应购买订单已创建，付款和发券由交易服务继续处理。
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
     * 查询当前用户拥有的抢券请求，按 MySQL 账本阶段返回建单进度。
     * <p>
     * INTENT 映射 UNKNOWN，ACCEPTED 映射 PROCESSING，CLOSED 映射 FAILED，SUCCEEDED 映射
     * SUCCESS。
     * 成功结果中的 orderId 指向购买订单，付款与发券接续交易流程。
     * 请求不存在或归属不符时抛出 REQUEST_NOT_FOUND。
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
        throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
    }
}

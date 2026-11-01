package com.voucherx.seckill.service;

import com.voucherx.admission.SeckillAdmissionService;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.mq.publisher.PublishResult;
import com.voucherx.mq.publisher.SeckillMessagePublisher;
import com.voucherx.order.entity.VoucherOrder;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.dto.SeckillRequestStatusResponse;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.model.SeckillRequestStatus;
import com.voucherx.voucher.entity.SeckillVoucher;
import com.voucherx.voucher.entity.StockMode;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * 秒杀入口编排：时间校验 -> Redis Lua 预扣 -> RabbitMQ 发布 -> 异步状态查询。
 * 注意：Redis 预扣与 MQ 发布不是一个原子事务。
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    private final SeckillVoucherMapper seckillVoucherMapper;
    private final VoucherOrderMapper orderMapper;
    private final SeckillStockService stockService;
    private final SeckillRequestStatusStore statusStore;
    private final SeckillMessagePublisher publisher;
    private final SeckillAdmissionService admissionService;

    public SeckillService(SeckillVoucherMapper seckillVoucherMapper,
                          VoucherOrderMapper orderMapper,
                          SeckillStockService stockService,
                          SeckillRequestStatusStore statusStore,
                          SeckillMessagePublisher publisher,
                          SeckillAdmissionService admissionService) {
        this.seckillVoucherMapper = seckillVoucherMapper;
        this.orderMapper = orderMapper;
        this.stockService = stockService;
        this.statusStore = statusStore;
        this.publisher = publisher;
        this.admissionService = admissionService;
    }

    public SeckillAcceptResponse seckill(Long userId, Long voucherId) {
        SeckillVoucher activity = seckillVoucherMapper.selectById(voucherId);
        if (activity == null) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        }

        long now = System.currentTimeMillis();
        long begin = toEpochMillis(activity.getBeginTime());
        long end = toEpochMillis(activity.getEndTime());
        if (now < begin) {
            throw new BusinessException(ErrorCode.SECKILL_NOT_STARTED);
        }
        if (now > end) {
            throw new BusinessException(ErrorCode.SECKILL_ENDED);
        }

        StockMode stockMode = StockMode.parse(activity.getStockMode());
        int bucketCount = activity.getBucketCount() == null ? 1 : activity.getBucketCount();
        int layoutVersion = activity.getLayoutVersion() == null ? 1 : activity.getLayoutVersion();
        if (stockMode == StockMode.BUCKET && bucketCount <= 0) {
            throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE, "库存布局不可用");
        }
        int startBucketNo = stockMode == StockMode.BUCKET
                ? Math.floorMod(userId, bucketCount)
                : 0;

        // 全局限流必须在任何库存/资格占用之前；活动不存在、未开始、结束的请求
        // 已在上面返回，不消耗全局令牌。
        admissionService.acquireGlobal(voucherId);

        String requestId = UUID.randomUUID().toString();
        long reserveResult = stockService.reserve(voucherId, userId, requestId, now, begin, end,
                stockMode.name(), startBucketNo, layoutVersion);
        handleReserveResult(reserveResult);

        String messageId = UUID.randomUUID().toString();
        SeckillOrderMessage message = new SeckillOrderMessage(
                messageId, requestId, userId, voucherId, now,
                stockMode.name(), startBucketNo, layoutVersion);

        PublishResult publishResult;
        try {
            publishResult = publisher.publish(message);
        } catch (Exception ex) {
            // 序列化等本地异常意味着消息尚未发送，可以安全释放预扣。
            stockService.release(voucherId, userId, requestId, "PUBLISH_LOCAL_ERROR");
            log.error("秒杀消息发布前发生本地异常，requestId={}", requestId, ex);
            throw new BusinessException(ErrorCode.SECKILL_PUBLISH_FAILED);
        }

        if (publishResult == PublishResult.CONFIRMED) {
            statusStore.markProcessing(requestId, messageId);
            return new SeckillAcceptResponse(requestId, SeckillRequestStatus.PROCESSING);
        }

        if (publishResult == PublishResult.UNKNOWN) {
            // 消息可能已经在队列中，不能释放库存，也不能报告成功或失败。
            statusStore.markUnknown(requestId, messageId, "PUBLISH_CONFIRM_UNKNOWN");
            log.warn("秒杀消息发布结果未知，保留 Redis 预扣等待查询/后续对账，requestId={}", requestId);
            return new SeckillAcceptResponse(requestId, SeckillRequestStatus.UNKNOWN);
        }

        // 明确未进入队列：幂等释放预扣。释放失败会留下残留资格，记录为已知限制。
        boolean released = stockService.release(voucherId, userId, requestId,
                "PUBLISH_" + publishResult.name());
        if (!released) {
            log.error("秒杀消息明确发布失败，但 Redis 预扣释放未完成，requestId={}, result={}",
                    requestId, publishResult);
        } else {
            log.info("秒杀消息明确发布失败，已释放 Redis 预扣，requestId={}, result={}",
                    requestId, publishResult);
        }
        throw new BusinessException(ErrorCode.SECKILL_PUBLISH_FAILED);
    }

    /**
     * 查询状态时先看 MySQL 已确定订单，再参考 Redis 过程状态。
     */
    public SeckillRequestStatusResponse queryRequest(Long userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        }

        VoucherOrder order = orderMapper.selectByRequestId(requestId);
        if (order != null) {
            if (!userId.equals(order.getUserId())) {
                throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
            }
            return new SeckillRequestStatusResponse(requestId, SeckillRequestStatus.SUCCESS,
                    order.getId(), order.getMessageId(), null);
        }

        Map<String, String> snapshot = statusStore.find(requestId);
        if (snapshot.isEmpty()) {
            return new SeckillRequestStatusResponse(requestId, SeckillRequestStatus.UNKNOWN,
                    null, null, "NO_STATUS_RECORD");
        }

        String owner = snapshot.get("userId");
        if (owner == null || !owner.equals(String.valueOf(userId))) {
            throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        }

        SeckillRequestStatus status = SeckillRequestStatus.parse(snapshot.get("status"));
        String messageId = snapshot.get("messageId");
        String reason = snapshot.get("reason");

        return switch (status) {
            case SUCCESS -> resolveSuccess(requestId, userId, messageId, snapshot.get("orderId"));
            case FAILED -> new SeckillRequestStatusResponse(
                    requestId, SeckillRequestStatus.FAILED, null, messageId, reason);
            case RELEASED -> new SeckillRequestStatusResponse(
                    requestId, SeckillRequestStatus.FAILED, null, messageId,
                    reason == null ? "RELEASED" : reason);
            case PENDING, PROCESSING -> new SeckillRequestStatusResponse(
                    requestId, SeckillRequestStatus.PROCESSING, null, messageId, null);
            case UNKNOWN -> new SeckillRequestStatusResponse(
                    requestId, SeckillRequestStatus.UNKNOWN, null, messageId, reason);
        };
    }

    private SeckillRequestStatusResponse resolveSuccess(String requestId, Long userId,
                                                        String messageId, String orderIdValue) {
        if (orderIdValue == null) {
            return new SeckillRequestStatusResponse(requestId, SeckillRequestStatus.UNKNOWN,
                    null, messageId, "ORDER_ID_MISSING");
        }
        try {
            VoucherOrder order = orderMapper.selectById(Long.valueOf(orderIdValue));
            if (order == null || !userId.equals(order.getUserId())) {
                return new SeckillRequestStatusResponse(requestId, SeckillRequestStatus.UNKNOWN,
                        null, messageId, "ORDER_NOT_CONFIRMED");
            }
            return new SeckillRequestStatusResponse(requestId, SeckillRequestStatus.SUCCESS,
                    order.getId(), order.getMessageId(), null);
        } catch (NumberFormatException ex) {
            return new SeckillRequestStatusResponse(requestId, SeckillRequestStatus.UNKNOWN,
                    null, messageId, "ORDER_ID_INVALID");
        }
    }

    private void handleReserveResult(long result) {
        if (result >= 0) {
            return;
        }
        switch ((int) result) {
            case -1 -> throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
            case -2 -> throw new BusinessException(ErrorCode.SECKILL_NOT_STARTED);
            case -3 -> throw new BusinessException(ErrorCode.SECKILL_ENDED);
            case -4 -> throw new BusinessException(ErrorCode.SECKILL_DUPLICATE);
            case -5 -> throw new BusinessException(ErrorCode.SECKILL_SOLD_OUT);
            default -> throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
        }
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}

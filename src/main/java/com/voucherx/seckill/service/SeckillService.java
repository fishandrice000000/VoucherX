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

@Service
public class SeckillService {
    private final SeckillVoucherMapper activities; private final VoucherOrderMapper orders;
    private final SeckillStockService stock; private final RequestLedger ledger;
    private final SeckillAdmissionService admission; private final Clock clock;
    public SeckillService(SeckillVoucherMapper activities,VoucherOrderMapper orders,SeckillStockService stock,
            RequestLedger ledger,SeckillAdmissionService admission,Clock clock) {
        this.activities=activities;this.orders=orders;this.stock=stock;this.ledger=ledger;this.admission=admission;this.clock=clock;
    }
    public SeckillAcceptResponse seckill(Long user,Long voucher) { return seckill(user,voucher,null); }
    public SeckillAcceptResponse seckill(Long user,Long voucher,String clientKey) {
        if(clientKey!=null && !clientKey.matches("[A-Za-z0-9._:-]{1,64}")) throw new BusinessException(ErrorCode.INVALID_PARAM,"Idempotency-Key 格式不合法");
        if(clientKey!=null) {
            RequestRecord old=ledger.byKey(user,clientKey);
            if(old!=null) return replay(old,voucher);
        }
        var activity=activities.selectById(voucher);
        if(activity==null) throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
        long now=clock.millis();
        long begin=activity.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end=activity.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        if(now<begin) throw new BusinessException(ErrorCode.SECKILL_NOT_STARTED);
        if(now>end) throw new BusinessException(ErrorCode.SECKILL_ENDED);
        StockMode mode=StockMode.parse(activity.getStockMode());
        int buckets=activity.getBucketCount()==null?1:activity.getBucketCount();
        if(buckets<1) throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
        int bucket=mode==StockMode.BUCKET?Math.floorMod(user,buckets):0;
        admission.acquireGlobal(voucher);
        String key=clientKey==null?UUID.randomUUID().toString():clientKey;
        RequestRecord r;
        try { r=ledger.intent(user,voucher,key,mode.name(),bucket,activity.getLayoutVersion()==null?1:activity.getLayoutVersion()); }
        catch(DuplicateKeyException ex) {
            r=ledger.byKey(user,key);if(r==null) throw ex;return replay(r,voucher);
        }
        long result;
        try { result=stock.reserve(voucher,user,r.requestId(),now,begin,end,mode.name(),bucket,r.layoutVersion(),r.epoch()); }
        catch(BusinessException ex) {
            // 预扣调用结果可能未知。意图已持久化，返回可查询 ID，由扫描核对，而非盲目释放。
            return new SeckillAcceptResponse(r.requestId(),SeckillRequestStatus.UNKNOWN);
        }
        if(result<0) {
            ledger.rejectUnreserved(r.requestId(),"RESERVE_"+result);
            throw new BusinessException(switch((int)result) {
                case -2 -> ErrorCode.SECKILL_NOT_STARTED;case -3 -> ErrorCode.SECKILL_ENDED;
                case -4 -> ErrorCode.SECKILL_DUPLICATE;case -5 -> ErrorCode.SECKILL_SOLD_OUT;
                default -> ErrorCode.SECKILL_STOCK_UNAVAILABLE;
            });
        }
        // 发生异常时保留 INTENT 和 Redis 归属证据，扫描可重新完成受理。
        ledger.accept(r.requestId());
        return new SeckillAcceptResponse(r.requestId(),SeckillRequestStatus.PROCESSING);
    }
    private SeckillAcceptResponse replay(RequestRecord r,long voucher) {
        if(r.voucherId()!=voucher) throw new BusinessException(ErrorCode.CONFLICT,"幂等键已绑定其他活动");
        return new SeckillAcceptResponse(r.requestId(),status(r));
    }
    private SeckillRequestStatus status(RequestRecord r) {
        return switch(r.phase()) {
            case "SUCCEEDED" -> SeckillRequestStatus.SUCCESS;
            case "CLOSED" -> SeckillRequestStatus.FAILED;
            case "ACCEPTED" -> SeckillRequestStatus.PROCESSING;
            default -> SeckillRequestStatus.UNKNOWN;
        };
    }
    public SeckillRequestStatusResponse queryRequest(Long user,String id) {
        RequestRecord r=ledger.find(id);
        if(r!=null) {
            if(r.userId()!=user) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
            return new SeckillRequestStatusResponse(id,status(r),r.orderId(),r.eventId(),r.reason());
        }
        var order=orders.selectByRequestId(id);
        if(order==null || !user.equals(order.getUserId())) throw new BusinessException(ErrorCode.REQUEST_NOT_FOUND);
        return new SeckillRequestStatusResponse(id,SeckillRequestStatus.SUCCESS,order.getId(),order.getMessageId(),null);
    }
}

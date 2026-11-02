package com.voucherx.commerce;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;

public final class CommerceRules {
    private CommerceRules() {}
    public static long discount(long owner,long user,long couponShop,long shop,long from,long until,
            long now,String state,long minSpend,long face,long original) {
        if(owner!=user) throw new BusinessException(ErrorCode.FORBIDDEN);
        if(original<=0 || face<=0 || minSpend<0 || original<minSpend || couponShop!=shop)
            throw new BusinessException(ErrorCode.INVALID_PARAM,"金额、门槛或商户不匹配");
        if(!"AVAILABLE".equals(state) || now<from || now>=until)
            throw new BusinessException(ErrorCode.CONFLICT,"优惠券当前不可用");
        return Math.min(face,original);
    }
    public static String releasedState(long until,long now) { return now>=until?"EXPIRED":"AVAILABLE"; }
    public static String paymentOutcome(String state,long deadline,long now,boolean success) {
        if(!success) return "FAILED_ATTEMPT";
        if("PAID".equals(state)) return "ALREADY_PAID";
        if(!"PENDING_PAYMENT".equals(state)) return "CONFLICT_TERMINAL";
        if(now>=deadline) return "CONFLICT_EXPIRED";
        return "APPLIED";
    }
}

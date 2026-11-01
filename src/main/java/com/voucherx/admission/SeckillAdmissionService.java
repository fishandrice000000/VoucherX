package com.voucherx.admission;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import org.springframework.stereotype.Service;

/**
 * 秒杀准入编排：本地突发保护 -> Redis 共享/活动预算。
 *
 * 所有拒绝都发生在库存预扣之前。调用方必须先完成认证和活动时间校验，再调用
 * acquireGlobal；本地桶可按入口顺序放在更前，但拒绝时会保守消耗一个本地令牌。
 */
@Service
public class SeckillAdmissionService {

    private final AdmissionProperties properties;
    private final LocalAdmissionGate localGate;
    private final RedisAdmissionStore redisStore;

    public SeckillAdmissionService(AdmissionProperties properties,
                                   LocalAdmissionGate localGate,
                                   RedisAdmissionStore redisStore) {
        this.properties = properties;
        this.localGate = localGate;
        this.redisStore = redisStore;
    }

    public void acquireLocal() {
        if (properties.mode() == AdmissionMode.OFF) {
            return;
        }
        if (!localGate.tryAcquire()) {
            throw new BusinessException(ErrorCode.SECKILL_BUSY);
        }
    }

    public void acquireGlobal(Long voucherId) {
        if (properties.mode() == AdmissionMode.OFF || !properties.global().enabled()) {
            return;
        }
        AdmissionDecision decision;
        try {
            decision = redisStore.tryAcquire(voucherId, properties);
        } catch (AdmissionUnavailableException ex) {
            throw new BusinessException(ErrorCode.SECKILL_ADMISSION_UNAVAILABLE,
                    "秒杀准入服务暂不可用", ex);
        }
        switch (decision) {
            case ALLOWED -> {
            }
            case SHARED_REJECTED, ACTIVITY_REJECTED, BURST_REJECTED ->
                    throw new BusinessException(ErrorCode.SECKILL_BUSY);
            case POLICY_STALE, INVALID_POLICY ->
                    throw new BusinessException(ErrorCode.SECKILL_ADMISSION_UNAVAILABLE,
                            "秒杀准入策略不可用，请稍后重试");
        }
    }

    public AdmissionMode mode() {
        return properties.mode();
    }
}

package com.voucherx.admission;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 请求准入编排：HTTP 入口执行本地突发保护，活动校验后申请 Redis 共享和活动预算。
 * <p>
 * 令牌代表按时间补充的新增工作预算。新请求通过准入后才写入意图和预留库存；
 * 入口各层独立计费，后续拒绝时已消耗的本地令牌保持已使用状态。
 * <p>
 * OFF 直接通过。FIXED 和 ADAPTIVE 可使用两层保护，ADAPTIVE 动态调整共享 Redis 策略。
 * 已受理请求由持久化账本及 Outbox 持续推进。
 *
 * @see com.voucherx.seckill.controller.SeckillController#seckill
 * @see com.voucherx.seckill.service.SeckillService#seckill(Long, Long, String)
 * @see RedisAdmissionStore
 */
@Service
public class SeckillAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(SeckillAdmissionService.class);

    private final AdmissionProperties properties;
    private final LocalAdmissionGate localGate;
    private final RedisAdmissionStore redisStore;

    public SeckillAdmissionService(AdmissionProperties properties,
            LocalAdmissionGate localGate,
            RedisAdmissionStore redisStore) {
        this.properties = properties;
        this.localGate = localGate;
        this.redisStore = redisStore;
        log.info("秒杀准入配置: mode={}, local={} ({} rps, cap {}), global={} "
                + "(shared {} rps/cap {}, activity {} rps/cap {})",
                properties.mode(), properties.local().enabled(),
                properties.local().ratePerSecond(), properties.local().capacity(),
                properties.global().enabled(), properties.global().sharedRate(),
                properties.global().sharedCapacity(), properties.global().activityRate(),
                properties.global().activityCapacity());
    }

    /**
     * 在 Controller 进入业务服务前申请本实例的一个令牌；OFF 或本地 gate 禁用时直接通过。
     * <p>
     * 桶内不足则立即抛 SECKILL_BUSY，HTTP 包装为 429/42920，不阻塞等待补充。
     * 同幂等键重试也先经过这里；本地令牌通过后若活动、全局预算或业务检查失败，不执行令牌退还。
     *
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             本实例突发预算不足
     * @see LocalAdmissionGate#tryAcquire()
     */
    public void acquireLocal() {
        if (properties.mode() == AdmissionMode.OFF) {
            return;
        }
        if (!localGate.tryAcquire()) {
            throw new BusinessException(ErrorCode.SECKILL_BUSY);
        }
    }

    /**
     * 为已通过活动校验的新请求申请共享桶和活动桶各一个令牌。
     * <p>
     * OFF 或 global.enabled=false 时直接通过。两桶预算同时满足才扣减；预算不足映射 429，
     * 策略缺失、失效、非法或 Redis 数据访问失败映射 503。
     * 该步骤位于新意图和库存预留前；同幂等键的既有请求由业务服务直接重放查询结果。
     *
     * @param voucherId
     *                      已通过活动校验的活动 ID
     * @throws com.voucherx.common.exception.BusinessException
     *                                                             预算不足或准入策略/依赖不可用
     * @see RedisAdmissionStore#tryAcquire
     */
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

    /**
     * 读取当前配置的准入模式，供观察或调用方判断，不触发令牌操作。
     *
     * @return OFF、FIXED 或 ADAPTIVE
     */
    public AdmissionMode mode() {
        return properties.mode();
    }
}

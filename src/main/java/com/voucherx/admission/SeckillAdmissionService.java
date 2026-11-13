package com.voucherx.admission;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 02 的请求侧编排：HTTP 入口先过本地突发保护，活动校验后再过 Redis 共享和活动预算。
 * <p>
 * 令牌表示当前允许进入多少新工作，会按时间补充；它不同于活动剩余库存。
 * 新请求被拒绝时尚未写意图或预留库存，但先前已消耗的本地令牌不会因后续拒绝而返还。
 * <p>
 * OFF 直接绕过准入。FIXED/ADAPTIVE 均可使用两层保护，ADAPTIVE 只动态调整共享 Redis 策略。
 * 已有可靠受理请求不会因入口降速被删除。
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
     * 活动存在且时间有效后，为本次新请求申请共享桶和活动桶各一个令牌。
     * <p>
     * OFF 或 global.enabled=false 时直接通过。两桶必须同时满足才扣减；预算不足映射 429，
     * 策略缺失/失效/非法或 Redis 数据访问失败映射 503，不因依赖异常静默放行。
     * 调用方应把此步骤放在新意图和库存预留前；同幂等键已命中旧请求时业务服务可在此前直接重放。
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

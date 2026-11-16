package com.voucherx.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.commerce.mapper.UserCouponMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 逐笔关闭超时未付订单，再批量标记到期的可用用户券。
 * <p>
 * 默认在上一轮结束后等待 10 秒继续扫描；维护模式下停用该任务。每笔 expire 通过服务代理完成独立事务。
 * 支付及用券入口同样校验有效期，扫描负责推进持久化到期状态。
 *
 * @see CommerceService#expire
 * @see CommerceRules#releasedState
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "voucherx.maintenance-mode", havingValue = "false", matchIfMissing = true)
public class CommerceExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(CommerceExpiryJob.class);
    private final UserCouponMapper coupons;
    private final CommerceService service;
    private final CommerceProperties config;
    private final Clock clock;

    public CommerceExpiryJob(UserCouponMapper coupons, CommerceService service, CommerceProperties config,
            Clock clock) {
        this.coupons = coupons;
        this.service = service;
        this.config = config;
        this.clock = clock;
    }

    /**
     * 分别扫描 PURCHASE 和 CONSUMPTION 的到期候选，每类最多 scanSize 条，再逐笔锁单复核并关闭。
     * <p>
     * 单笔失败记录日志后继续其他候选；扫描异常结束当前轮次，后续轮次继续处理。
     * 最后有界更新到期 AVAILABLE 券；LOCKED 券经所属消费单的关闭流程释放。
     *
     * @see com.voucherx.commerce.mapper.UserCouponMapper#expireAvailable
     */
    @Scheduled(fixedDelayString = "${voucherx.commerce.expiry-interval-millis:10000}")
    public void tick() {
        try {
            for (var kind : CommerceService.OrderKind.values()) {
                var ids = service.expiredOrderIds(kind, clock.millis(), config.scanSize());
                for (long id : ids)
                    try {
                        service.expire(kind, id);
                    } catch (Exception ex) {
                        log.warn("超时关单待重试，kind={},id={}", kind, id, ex);
                    }
            }
            coupons.expireAvailable(clock.millis(), config.scanSize());
        } catch (Exception ex) {
            log.warn("过期扫描失败", ex);
        }
    }
}

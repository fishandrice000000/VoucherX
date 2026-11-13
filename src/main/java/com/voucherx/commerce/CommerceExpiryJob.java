package com.voucherx.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.commerce.mapper.UserCouponMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 周期扫描过期交易：逐个关闭超时未付订单，并分批把可用但已到期的券标为 EXPIRED。
 * <p>
 * 每笔关单通过独立服务事务加锁复核；扫描不是唯一时间校验点，付款和用券路径也检查截止时间。
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
     * 分别扫描 PURCHASE、CONSUMPTION 的有限候选集并逐个调用 expire；单笔失败记日志待后续重试，最后清理到期 AVAILABLE 券。
     */
    @Scheduled(fixedDelayString = "${voucherx.commerce.expiry-interval-millis:10000}")
    public void tick() {
        try {
            for (var kind : CommerceService.Kind.values()) {
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

package com.voucherx.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.commerce.mapper.UserCouponMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

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

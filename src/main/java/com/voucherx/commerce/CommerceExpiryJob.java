package com.voucherx.commerce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.voucherx.commerce.mapper.UserCouponMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 04 的到期清理任务：逐笔关闭超时未付订单，再批量标记可用但已到期的用户券。
 * <p>
 * 默认固定延迟 10 秒，maintenance-mode 时不创建；模拟支付关闭不等于关闭到期扫描。
 * 每笔 expire 经独立服务代理处理自己的事务，整个扫描没有一个跨所有订单的大事务。
 * <p>
 * 任务只负责推进持久化状态，付款和用券路径也按时间判断，不能把扫描恰好准时作为可用性前提。
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
     * 分别对 PURCHASE、CONSUMPTION 扫描最多 scanSize 个到期候选，再逐笔 expire 锁单复核并关闭。
     * <p>
     * 单笔异常记日志后继续其他候选；外层扫描异常结束本轮，后续轮次再试。
     * 最后另执行一条有界 expireAvailable，只修改 AVAILABLE 券；LOCKED 券要经过所属消费单关闭才能安全释放。
     * 固定延迟是上轮结束后再等待配置间隔，既不是每单截止时刻的定时回调，也不限制整轮执行耗时。
     *
     * @see com.voucherx.commerce.mapper.UserCouponMapper#expireAvailable
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

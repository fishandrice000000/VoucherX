package com.voucherx.fulfillment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

/**
 * 提供受理、Outbox 和交易服务共享注入的业务时钟；需要绝对业务时刻的路径使用其 Unix 毫秒。
 */
@Configuration
public class DeliveryConfig {
    /**
     * 创建 UTC 系统时钟，供服务通过 Clock.millis 取得绝对时间，测试可替换时钟 Bean。
     * <p>
     * 这里不设置 JVM 的默认时区，也不提供消费者耗时使用的单调 nanoTime。
     *
     * @return 注入业务服务的系统 Clock
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}

package com.voucherx.fulfillment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class DeliveryConfig {
    @Bean public Clock businessClock() { return Clock.systemUTC(); }
}

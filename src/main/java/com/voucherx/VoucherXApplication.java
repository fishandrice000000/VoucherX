package com.voucherx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VoucherXApplication {

    public static void main(String[] args) {
        SpringApplication.run(VoucherXApplication.class, args);
    }
}

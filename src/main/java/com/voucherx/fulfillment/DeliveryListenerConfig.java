package com.voucherx.fulfillment;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置消费者的手动确认和小批次交付方式；默认一次一条，由业务代码决定提交后何时 ACK。
 */
@Configuration
public class DeliveryListenerConfig {
    /**
     * 在 Boot 默认配置上启用 MANUAL ACK 和批监听；实际批大小由开关决定，预取数与事务批次是不同配置。
     *
     * @param connection
     *                       RabbitMQ 连接工厂
     * @param configurer
     *                       Spring Boot 默认监听配置
     * @param properties
     *                       投递和批处理配置
     *
     * @return 主队列监听器使用的容器工厂
     */
    @Bean
    public SimpleRabbitListenerContainerFactory deliveryContainerFactory(ConnectionFactory connection,
            SimpleRabbitListenerContainerFactoryConfigurer configurer, DeliveryProperties properties) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connection);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(properties.batchEnabled() ? properties.batchSize() : 1);
        factory.setReceiveTimeout((long) properties.batchWaitMillis());
        factory.setPrefetchCount(Math.max(10, properties.batchSize()));
        return factory;
    }
}

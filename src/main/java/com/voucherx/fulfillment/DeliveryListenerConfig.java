package com.voucherx.fulfillment;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DeliveryListenerConfig {
    @Bean
    public SimpleRabbitListenerContainerFactory deliveryContainerFactory(ConnectionFactory connection,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,DeliveryProperties properties) {
        var factory=new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connection);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(properties.batchEnabled()?properties.batchSize():1);
        factory.setReceiveTimeout((long)properties.batchWaitMillis());
        factory.setPrefetchCount(Math.max(10,properties.batchSize()));
        return factory;
    }
}

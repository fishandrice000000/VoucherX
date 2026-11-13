package com.voucherx.fulfillment;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主队列监听容器：手动确认、列表形式交付，以及按配置启用的小批次聚合。
 * <p>
 * 容器交付列表后由消费者再限制总字节数，最后由订单服务决定数据库事务边界。
 * 手动确认意味着成功路径在事务提交后才 basicAck，拒绝路径由业务代码选择 ACK 或 NACK。
 *
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer#onMessages
 */
@Configuration
public class DeliveryListenerConfig {
    /**
     * 先应用 Boot 的连接/监听默认配置，再显式启用 MANUAL ACK、批监听和消费端聚合。
     * <p>
     * batchEnabled=false 时容器批大小为 1；开启时取 batchSize。prefetch 为 max(10, 配置 batchSize)，
     * 即使批开关关闭也按此公式设置，预取不等于同事务处理条数。
     * receiveTimeout 使用 batchWaitMillis，只是接收等待配置，不承诺端到端或批事务耗时不超过这个值。
     *
     * @param connection
     *                       RabbitMQ 连接工厂
     * @param configurer
     *                       Spring Boot 默认监听容器配置器
     * @param properties
     *                       投递和批处理配置
     * @return 名为 deliveryContainerFactory 的主队列监听工厂
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

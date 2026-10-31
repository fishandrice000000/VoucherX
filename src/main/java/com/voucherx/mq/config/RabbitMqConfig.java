package com.voucherx.mq.config;

import com.voucherx.config.MqProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：
 * 主队列失败 -> 死信交换机 -> 死信队列；
 * 临时失败 -> 重试交换机 -> TTL 重试队列 -> 过期后回到主交换机。
 */
@Configuration
public class RabbitMqConfig {

    public static final String MAIN_EXCHANGE = "voucherx.seckill.exchange";
    public static final String MAIN_QUEUE = "voucherx.seckill.order.queue";
    public static final String MAIN_ROUTING_KEY = "voucherx.seckill.order";

    public static final String DLX_EXCHANGE = "voucherx.seckill.dlx";
    public static final String DEAD_QUEUE = "voucherx.seckill.order.dlq";
    public static final String DEAD_ROUTING_KEY = "voucherx.seckill.order.dead";

    public static final String RETRY_EXCHANGE = "voucherx.seckill.retry.exchange";
    public static final String RETRY_QUEUE = "voucherx.seckill.order.retry.queue";
    public static final String RETRY_ROUTING_KEY = "voucherx.seckill.order.retry";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(MAIN_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(MAIN_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillExchange).with(MAIN_ROUTING_KEY);
    }

    @Bean
    public DirectExchange seckillDeadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding seckillDeadLetterBinding(Queue seckillDeadLetterQueue,
                                             DirectExchange seckillDeadLetterExchange) {
        return BindingBuilder.bind(seckillDeadLetterQueue)
                .to(seckillDeadLetterExchange)
                .with(DEAD_ROUTING_KEY);
    }

    @Bean
    public DirectExchange seckillRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillRetryQueue(MqProperties mqProperties) {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", mqProperties.retryTtlMillis())
                .withArgument("x-dead-letter-exchange", MAIN_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MAIN_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding seckillRetryBinding(Queue seckillRetryQueue, DirectExchange seckillRetryExchange) {
        return BindingBuilder.bind(seckillRetryQueue)
                .to(seckillRetryExchange)
                .with(RETRY_ROUTING_KEY);
    }
}

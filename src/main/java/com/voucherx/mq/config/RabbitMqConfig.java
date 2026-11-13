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
 * 声明秒杀消息的主交换机、主队列、死信队列及 TTL 重试拓扑。
 * <p>
 * 正常主线：Outbox → MAIN_EXCHANGE/MAIN_ROUTING_KEY → MAIN_QUEUE → 订单消费者。
 * 主队列拒绝且不重排的消息进入死信队列；保留的 TTL 重试队列到期后回主交换机。
 * 当前暂时性建单失败主要通过数据库 Outbox 调度重发，不能把所有重试都理解为 TTL 队列。
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

    /**
     * 创建持久化 Direct 主交换机；按精确路由键分发订单消息。
     *
     * @return 主交换机
     */
    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(MAIN_EXCHANGE, true, false);
    }

    /**
     * 创建持久化主队列，配置拒绝消息进入死信交换机及指定死信路由键。
     *
     * @return 主消费队列
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(MAIN_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    /**
     * 将主队列以 MAIN_ROUTING_KEY 绑定到主交换机。
     *
     * @param seckillOrderQueue
     *                              主队列 Bean
     * @param seckillExchange
     *                              主交换机 Bean
     *
     * @return 主消费绑定
     */
    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillExchange).with(MAIN_ROUTING_KEY);
    }

    /**
     * 创建主队列拒绝消息的持久化死信交换机。
     *
     * @return 死信交换机
     */
    @Bean
    public DirectExchange seckillDeadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    /**
     * 创建持久化死信队列，保留被拒绝的消息供诊断。
     *
     * @return 死信队列
     */
    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    /**
     * 将死信队列绑定到死信路由键。
     *
     * @param seckillDeadLetterQueue
     *                                      死信队列 Bean
     * @param seckillDeadLetterExchange
     *                                      死信交换机 Bean
     *
     * @return 死信绑定
     */
    @Bean
    public Binding seckillDeadLetterBinding(Queue seckillDeadLetterQueue,
            DirectExchange seckillDeadLetterExchange) {
        return BindingBuilder.bind(seckillDeadLetterQueue)
                .to(seckillDeadLetterExchange)
                .with(DEAD_ROUTING_KEY);
    }

    /**
     * 创建保留的 TTL 重试交换机。
     *
     * @return 重试交换机
     */
    @Bean
    public DirectExchange seckillRetryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    /**
     * 创建带 TTL 的持久化重试队列；消息过期后经死信机制回到主交换机。
     *
     * @param mqProperties
     *                         提供重试消息 TTL 的配置
     *
     * @return TTL 重试队列
     */
    @Bean
    public Queue seckillRetryQueue(MqProperties mqProperties) {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", mqProperties.retryTtlMillis())
                .withArgument("x-dead-letter-exchange", MAIN_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MAIN_ROUTING_KEY)
                .build();
    }

    /**
     * 把重试队列绑定到重试交换机，供显式 publishRetry 使用。
     *
     * @param seckillRetryQueue
     *                                 重试队列 Bean
     * @param seckillRetryExchange
     *                                 重试交换机 Bean
     *
     * @return 重试绑定
     */
    @Bean
    public Binding seckillRetryBinding(Queue seckillRetryQueue, DirectExchange seckillRetryExchange) {
        return BindingBuilder.bind(seckillRetryQueue)
                .to(seckillRetryExchange)
                .with(RETRY_ROUTING_KEY);
    }
}

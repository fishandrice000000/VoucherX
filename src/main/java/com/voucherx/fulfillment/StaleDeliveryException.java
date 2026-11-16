package com.voucherx.fulfillment;

/**
 * 表示身份匹配的交付当前受活动或请求执行条件限制。
 * <p>
 * 触发条件包括活动冻结、代次失效及请求阶段尚未就绪。消费者确认本次交付，持久化请求继续由核对或恢复调度推进。
 * 消息身份不匹配由守卫分类为 OrderBusinessException。
 *
 * @see RequestLedger#guard
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 */
public class StaleDeliveryException extends RuntimeException {
}

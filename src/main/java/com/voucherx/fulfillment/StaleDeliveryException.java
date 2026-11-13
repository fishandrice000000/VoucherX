package com.voucherx.fulfillment;

/**
 * 账本守卫表示本次投递当前不宜继续执行：身份匹配后活动冻结、代次失效，或业务阶段尚不允许建单。
 * <p>
 * 消费者捕获后仅 ACK 这次旧投递，不把原请求关闭为失败，也不覆盖状态缓存。
 * 继续处理依赖原持久化请求和后续核对/恢复调度；抛出本异常本身不会自动发送一条新消息。
 * 若消息身份已经与账本不匹配，守卫会先抛 OrderBusinessException，属于另一条分支。
 *
 * @see RequestLedger#guard
 * @see com.voucherx.mq.consumer.SeckillOrderConsumer
 */
public class StaleDeliveryException extends RuntimeException {
}

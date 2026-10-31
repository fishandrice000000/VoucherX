package com.voucherx.seckill.model;

/**
 * 异步订单消息。messageId 用于链路追踪，requestId 用于业务幂等，二者不能混用。
 */
public record SeckillOrderMessage(
        String messageId,
        String requestId,
        Long userId,
        Long voucherId,
        long createdAt
) {
}

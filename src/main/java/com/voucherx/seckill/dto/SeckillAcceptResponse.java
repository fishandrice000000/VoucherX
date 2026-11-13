package com.voucherx.seckill.dto;

import com.voucherx.seckill.model.SeckillRequestStatus;

/**
 * 抢券受理响应，供客户端保存 requestId 并轮询结果。
 * <p>
 * 新请求 PROCESSING 表示 ACCEPTED 与 Outbox 已提交，不要求消息此刻已到 RabbitMQ；
 * UNKNOWN 表示预留等中间结果尚待核对。复用幂等键时还可能返回原请求的 SUCCESS/FAILED。
 *
 * @param requestId
 *                      可查询的抢券请求 ID
 * @param status
 *                      当前建单阶段状态；不是订单付款状态
 */
public record SeckillAcceptResponse(
        String requestId,
        SeckillRequestStatus status) {
}

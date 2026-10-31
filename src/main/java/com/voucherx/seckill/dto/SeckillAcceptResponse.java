package com.voucherx.seckill.dto;

import com.voucherx.seckill.model.SeckillRequestStatus;

/**
 * 秒杀请求受理结果。PROCESSING 表示消息已确认进入 RabbitMQ；UNKNOWN 表示发布确认超时等无法确定状态。
 */
public record SeckillAcceptResponse(
        String requestId,
        SeckillRequestStatus status
) {
}

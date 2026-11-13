package com.voucherx.seckill.dto;

import com.voucherx.seckill.model.SeckillRequestStatus;

/**
 * 异步结果查询。MySQL 已有订单时一定返回 SUCCESS；无法判断时不臆测成功或失败。
 */
public record SeckillRequestStatusResponse(
        String requestId,
        SeckillRequestStatus status,
        Long orderId,
        String messageId,
        String reason) {
}

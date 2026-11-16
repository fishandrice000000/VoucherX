package com.voucherx.seckill.dto;

import com.voucherx.seckill.model.SeckillRequestStatus;

/**
 * 抢券建单结果查询响应；只以 MySQL 请求账本为依据，缺失时返回请求不存在。
 * <p>
 * SUCCESS 表示购买订单已经创建，不代表已付款、发券或核销。
 *
 * @param requestId
 *                      抢券请求 ID
 * @param status
 *                      当前请求状态
 * @param orderId
 *                      建单成功时的购买订单 ID，否则可空
 * @param eventId
 *                      对应的固定事件 ID
 * @param reason
 *                      关闭或诊断原因，可空
 */
public record SeckillRequestStatusResponse(
        String requestId,
        SeckillRequestStatus status,
        Long orderId,
        String eventId,
        String reason) {
}

package com.voucherx.fulfillment;

import com.voucherx.seckill.model.SeckillOrderMessage;

public record RequestRecord(String requestId, String eventId, long userId, long voucherId,
        String phase, String reservation, long epoch, String stockMode, int startBucketNo,
        int layoutVersion, Long orderId, String reason, long createdAt, long updatedAt) {
    public boolean terminal() {
        return "SUCCEEDED".equals(phase) || "CLOSED".equals(phase);
    }

    public SeckillOrderMessage message() {
        return new SeckillOrderMessage(eventId, requestId, userId,
                voucherId, createdAt, stockMode, startBucketNo, layoutVersion, epoch);
    }

    public boolean matches(SeckillOrderMessage m) {
        return requestId.equals(m.requestId()) && eventId.equals(m.messageId()) && userId == m.userId()
                && voucherId == m.voucherId() && epoch == m.epoch() && stockMode.equals(m.stockMode())
                && Integer.valueOf(startBucketNo).equals(m.startBucketNo())
                && Integer.valueOf(layoutVersion).equals(m.layoutVersion());
    }
}

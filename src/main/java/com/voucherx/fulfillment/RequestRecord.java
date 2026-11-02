package com.voucherx.fulfillment;

import com.voucherx.seckill.model.SeckillOrderMessage;
import java.sql.ResultSet;
import java.sql.SQLException;

public record RequestRecord(String requestId, String eventId, long userId, long voucherId,
        String phase, String reservation, long epoch, String stockMode, int startBucketNo,
        int layoutVersion, Long orderId, String reason, long createdAt, long updatedAt) {
    public boolean terminal() { return "SUCCEEDED".equals(phase) || "CLOSED".equals(phase); }
    public SeckillOrderMessage message() { return new SeckillOrderMessage(eventId, requestId, userId,
            voucherId,createdAt,stockMode,startBucketNo,layoutVersion,epoch); }
    public boolean matches(SeckillOrderMessage m) {
        return requestId.equals(m.requestId()) && eventId.equals(m.messageId()) && userId == m.userId()
                && voucherId == m.voucherId() && epoch == m.epoch() && stockMode.equals(m.stockMode())
                && Integer.valueOf(startBucketNo).equals(m.startBucketNo()) && Integer.valueOf(layoutVersion).equals(m.layoutVersion());
    }
    public static RequestRecord read(ResultSet rs,int row) throws SQLException {
        return new RequestRecord(rs.getString("request_id"),rs.getString("event_id"),rs.getLong("user_id"),
                rs.getLong("voucher_id"),rs.getString("phase"),rs.getString("reservation"),rs.getLong("epoch"),
                rs.getString("stock_mode"),rs.getInt("start_bucket_no"),rs.getInt("layout_version"),
                rs.getObject("order_id",Long.class),rs.getString("reason"),rs.getLong("created_at_ms"),rs.getLong("updated_at_ms"));
    }
}

package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 统一锁顺序：活动共享锁 -> 请求行 -> 库存行。冻结使用活动独占锁。 */
@Component
public class ActivityGate {
    private final JdbcTemplate db;
    public ActivityGate(JdbcTemplate db) { this.db = db; }
    public record Control(String state, long epoch) {}
    public Control lock(long voucherId) {
        // 首次建行先普通读取/INSERT IGNORE，避免在不存在行上持共享间隙锁后升级插入。
        if (db.queryForObject("SELECT COUNT(*) FROM tb_activity_control WHERE voucher_id=?",Long.class,voucherId)==0)
            db.update("INSERT IGNORE INTO tb_activity_control(voucher_id) VALUES (?)",voucherId);
        var rows = db.query("SELECT state,epoch FROM tb_activity_control WHERE voucher_id=? FOR SHARE",
                (rs,n) -> new Control(rs.getString(1),rs.getLong(2)),voucherId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"活动控制记录不可用");
        return rows.getFirst();
    }
    public long requireNormal(long voucherId) {
        Control c = lock(voucherId);
        if (!"NORMAL".equals(c.state())) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,"活动正在恢复，暂不受理");
        return c.epoch();
    }
    public boolean normal(long voucherId, long epoch) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)=1 FROM tb_activity_control WHERE voucher_id=? AND state='NORMAL' AND epoch=?",
                Boolean.class,voucherId,epoch));
    }
}

package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.mapper.ActivityControlMapper;
import org.springframework.stereotype.Component;

/** 统一锁顺序：活动共享锁 -> 请求行 -> 库存行。冻结使用活动独占锁。 */
@Component
public class ActivityGate {
    private final ActivityControlMapper mapper;

    public ActivityGate(ActivityControlMapper mapper) {
        this.mapper = mapper;
    }

    public record Control(String state, long epoch) {
    }

    public Control lock(long voucherId) {
        // 首次建行先普通读取/INSERT IGNORE，避免在不存在行上持共享间隙锁后升级插入。
        if (mapper.count(voucherId) == 0)
            mapper.insertIfAbsent(voucherId);
        Control control = mapper.lockShared(voucherId);
        if (control == null)
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "活动控制记录不可用");
        return control;
    }

    public long requireNormal(long voucherId) {
        Control c = lock(voucherId);
        if (!"NORMAL".equals(c.state()))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "活动正在恢复，暂不受理");
        return c.epoch();
    }

    public boolean normal(long voucherId, long epoch) {
        return mapper.isNormal(voucherId, epoch);
    }
}

package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.mapper.ActivityControlMapper;
import org.springframework.stereotype.Component;

/**
 * 用 MySQL 活动控制行把正常处理与冻结恢复协调起来。
 * <p>
 * 正常链路按“活动共享锁 → 请求行 → 库存行”加锁；恢复流程通过活动独占锁与其互斥。
 * 共享锁需在调用者事务中持有，epoch 用于识别恢复前留下的旧请求和旧消息。
 */
@Component
public class ActivityGate {
    private final ActivityControlMapper mapper;

    public ActivityGate(ActivityControlMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 活动控制行的只读快照。
     *
     * @param state
     *                  NORMAL、FROZEN 等控制状态
     * @param epoch
     *                  活动当前恢复代次；与请求及消息中的代次比较
     */
    public record Control(String state, long epoch) {
    }

    /**
     * 确保控制行存在，再以 FOR SHARE 读取；仅加锁，不要求状态一定是 NORMAL。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 控制状态及当前代次；锁持续到调用者事务结束
     */
    public Control lock(long voucherId) {
        // 首次建行先普通读取/INSERT IGNORE，避免在不存在行上持共享间隙锁后升级插入。
        if (mapper.count(voucherId) == 0)
            mapper.insertIfAbsent(voucherId);
        Control control = mapper.lockShared(voucherId);
        if (control == null)
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "活动控制记录不可用");
        return control;
    }

    /**
     * 持有活动共享锁并要求活动处于 NORMAL，否则停止受理。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 可写入新请求的当前恢复代次
     */
    public long requireNormal(long voucherId) {
        Control c = lock(voucherId);
        if (!"NORMAL".equals(c.state()))
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "活动正在恢复，暂不受理");
        return c.epoch();
    }

    /**
     * 无行锁地检查活动是否仍为 NORMAL 且代次一致，供发送前快速过滤。
     * <p>
     * 检查后状态仍可能变化；消费者建单时还必须在事务内执行最终守卫。
     *
     * @param voucherId
     *                      活动 ID
     * @param epoch
     *                      待发送请求记录的代次
     *
     * @return 当前状态及代次均匹配时为 true
     */
    public boolean normal(long voucherId, long epoch) {
        return mapper.isNormal(voucherId, epoch);
    }
}

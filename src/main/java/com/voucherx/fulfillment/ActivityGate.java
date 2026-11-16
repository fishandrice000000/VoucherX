package com.voucherx.fulfillment;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.fulfillment.mapper.ActivityControlMapper;
import org.springframework.stereotype.Component;

/**
 * 通过 MySQL 活动控制行协调正常发行、初始化和活动恢复。
 * <p>
 * 正常写入按活动共享锁、请求行锁、库存行的顺序执行；初始化和恢复通过活动独占锁与其互斥。
 * 共享锁由调用者事务保持，epoch 参与活动、请求和消息的身份校验。
 * 意图提交、Redis 预留、受理提交各有执行边界；各阶段核对当前状态与代次。
 * <p>
 * 控制状态保护受理、建单与预留补偿等发行流程；付款和核销依据各自订单、用户券状态推进。
 *
 * @see com.voucherx.recovery.RecoveryService#freeze(long, String)
 * @see RequestLedger
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
    public Control lockActivityForShare(long voucherId) {
        // 首次建行先普通读取/INSERT IGNORE，避免在不存在行上持共享间隙锁后升级插入。
        if (mapper.count(voucherId) == 0)
            mapper.insertIfAbsent(voucherId);
        Control control = mapper.selectByVoucherIdForShare(voucherId);
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
    public long lockNormalActivityAndGetEpoch(long voucherId) {
        Control c = lockActivityForShare(voucherId);
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

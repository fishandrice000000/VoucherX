package com.voucherx.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.fulfillment.ActivityGate.Control;

/**
 * 活动控制行的普通读取与共享锁访问；冻结恢复使用该控制行协调并发。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface ActivityControlMapper {
    /**
     * 检查活动控制行是否存在，不加锁。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 存在时为 1，不存在为 0
     */
    long count(@Param("voucherId") long voucherId);

    /**
     * INSERT IGNORE 创建缺失控制行，状态及代次使用表默认值。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 新插入为 1；已存在为 0
     */
    int insertIfAbsent(@Param("voucherId") long voucherId);

    /**
     * FOR SHARE 读取状态及代次，锁由调用者事务保持；本方法不要求 NORMAL。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 控制快照；不存在为 null
     */
    Control lockShared(@Param("voucherId") long voucherId);

    /**
     * 普通查询指定活动是否 NORMAL 且 epoch 匹配；这是瞬时检查。
     *
     * @param voucherId
     *                      活动 ID
     * @param epoch
     *                      要核对的恢复代次
     *
     * @return 状态及代次匹配时为 true
     */
    boolean isNormal(@Param("voucherId") long voucherId, @Param("epoch") long epoch);

    /**
     * 统计该活动是否存在非 NORMAL 控制行，供安全初始化拒绝自动重建。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 非 NORMAL 时为 1，否则为 0
     */
    long countFrozen(@Param("voucherId") long voucherId);
}

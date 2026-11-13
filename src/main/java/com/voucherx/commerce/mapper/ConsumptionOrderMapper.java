package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.ConsumptionOrder;
import com.voucherx.commerce.model.GeneratedId;

/**
 * 消费订单访问：记录原价、优惠额、应付、锁券令牌和付款期限。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface ConsumptionOrderMapper {
    /**
     * 插入消费单金额、用户券关联、锁令牌及付款期限，SQL 使用表默认 PENDING_PAYMENT。
     * <p>
     * 不把草稿 order.id 写入数据库；useGeneratedKeys 将实际自增键回填到 key.id，随后才能把同一个消费单 ID 写到券锁。
     * 事务还须覆盖后续券行锁、tryLock 和 LOCK 动作，失败时本次插单回滚。
     *
     * @param order
     *                  不可变消费单草稿，含服务层计算的原额/优惠/实付
     * @param key
     *                  接收数据库主键的可变载体
     * @return 插入行数，不是订单 ID；主键从 key.getId 读取
     * @see GeneratedId
     * @see com.voucherx.commerce.CommerceService#consume
     */
    int insert(@Param("order") ConsumptionOrder order, @Param("key") GeneratedId key);

    /**
     * 普通查询消费单，不验证用户归属。
     *
     * @param id
     *               消费订单 ID
     * @return 消费订单；不存在为 null
     */
    ConsumptionOrder find(@Param("id") long id);

    /**
     * FOR UPDATE 锁消费单，付款/取消/超时均按先订单、后券的顺序进入，具体用户归属由服务层验证。
     *
     * @param id
     *               消费订单 ID
     * @return 消费单快照；不存在为 null，锁保持到调用者事务结束
     */
    ConsumptionOrder lock(@Param("id") long id);

    /**
     * 仅把 PENDING_PAYMENT 改为 PAID；服务层检查更新 1 行，并把券 USED、USE 动作和支付事件放在同一事务。
     *
     * @param id
     *               已持锁且允许付款的消费单 ID
     * @return 成功转移为 1，状态不匹配或不存在为 0
     */
    int markPaid(@Param("id") long id);

    /**
     * 条件关闭仍待付的消费单；不在本条 SQL 中操作券，释放与 RELEASE 动作由持锁服务在同事务完成。
     *
     * @param id
     *               已持锁且允许关闭的消费单 ID
     * @return 成功转移为 1，状态不匹配或不存在为 0
     */
    int cancel(@Param("id") long id);

    /**
     * 按截止时间扫描已到期且待付的消费单；仅提供候选，真正关闭时再次加锁检查。
     *
     * @param now
     *                  扫描时刻，Unix 毫秒
     * @param limit
     *                  最多返回条数
     * @return 有界候选订单 ID 列表
     */
    List<Long> expiredIds(@Param("now") long now, @Param("limit") int limit);
}

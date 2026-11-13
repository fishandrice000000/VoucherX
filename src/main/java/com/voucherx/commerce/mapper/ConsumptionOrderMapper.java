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
     * 插入待付消费单；XML 的 useGeneratedKeys 将自增主键写入 key.id，不修改不可变 order 草稿。
     *
     * @param order
     *                  尚未分配数据库主键的不可变消费单草稿
     * @param key
     *                  接收数据库自增主键的可变载体
     *
     * @return 插入行数；主键另从 key.getId() 获取
     */
    int insert(@Param("order") ConsumptionOrder order, @Param("key") GeneratedId key);

    /**
     * 普通查询消费单，不验证用户归属。
     *
     * @param id
     *               消费订单 ID
     *
     * @return 消费订单；不存在为 null
     */
    ConsumptionOrder find(@Param("id") long id);

    /**
     * FOR UPDATE 锁消费单；需要与券一起操作时先锁订单、再锁券。
     *
     * @param id
     *               消费订单 ID
     *
     * @return 消费订单；不存在为 null
     */
    ConsumptionOrder lock(@Param("id") long id);

    /**
     * 仅将 PENDING_PAYMENT 更新为 PAID；须与核销和支付事件保持同事务。
     *
     * @param id
     *               消费订单 ID
     *
     * @return 成功转移为 1，状态不匹配为 0
     */
    int markPaid(@Param("id") long id);

    /**
     * 仅将 PENDING_PAYMENT 更新为 CANCELLED；释放券锁由服务层在同事务中另行执行。
     *
     * @param id
     *               消费订单 ID
     *
     * @return 成功转移为 1，状态不匹配为 0
     */
    int cancel(@Param("id") long id);

    /**
     * 按截止时间扫描已到期且待付的消费单；仅提供候选，真正关闭时再次加锁检查。
     *
     * @param now
     *                  扫描时刻，Unix 毫秒
     * @param limit
     *                  最多返回条数
     *
     * @return 有界候选订单 ID 列表
     */
    List<Long> expiredIds(@Param("now") long now, @Param("limit") int limit);
}

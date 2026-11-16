package com.voucherx.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.fulfillment.OutboxClaim;

/**
 * 可靠投递的数据库事件表访问；同一业务事件重发沿用原 eventId。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface OutboxMapper {
    /**
     * 与请求受理一起插入事件；默认 PENDING，下一发送时间设为当前时刻。
     *
     * @param eventId
     *                      固定事件 ID
     * @param requestId
     *                      抢券请求 ID
     * @param now
     *                      当前 Unix 毫秒
     * @return 插入行数
     */
    int insert(@Param("eventId") String eventId, @Param("requestId") String requestId, @Param("now") long now);

    /**
     * 普通查询到期 PENDING 或租约过期 CLAIMED，关联请求和控制行确认 NORMAL、代次相等且请求 ACCEPTED。
     * <p>
     * 只给候选、不锁定领取资格；查询后条件仍可能改变，调用者须再 claim。
     * SQL 未用 maxAttempts 过滤认领次数，次数隔离由失败处理分支负责。
     *
     * @param now
     *                  本轮扫描 Unix 毫秒
     * @param limit
     *                  候选数量上限
     * @return 按 next_at_ms 排序的有限事件 ID 列表
     */
    List<String> dueEvents(@Param("now") long now, @Param("limit") int limit);

    /**
     * 按事件到期或租约过期条件竞争认领，成功时写 CLAIMED、新令牌、租约截止并累计 attempts。
     * <p>
     * 发送器随后核对活动和请求，消费者在建单事务中作最终守卫；认领允许重复发送，由业务身份及订单幂等处理。
     *
     * @param id
     *                       固定事件 ID
     * @param token
     *                       本次随机认领令牌
     * @param leaseUntil
     *                       本次租约截止 Unix 毫秒
     * @param now
     *                       判断是否已到可认领时刻的 Unix 毫秒
     * @return 1 表示本次条件更新成功；0 表示条件已不匹配
     */
    int claim(@Param("id") String id, @Param("token") String token, @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    /**
     * 读取已认领事件关联的请求 ID 和尝试次数；本查询不校验令牌。
     *
     * @param id
     *               Outbox 事件 ID
     * @return 事件摘要；不存在为 null
     */
    OutboxClaim findClaim(@Param("id") String id);

    /**
     * 按 event_id、claim_token 和 CLAIMED 条件更新发布结果及 next_at_ms，并清空令牌。
     * <p>
     * 令牌保持匹配即可回写；接手或 defer 更换令牌后，先前发送者的更新数为 0。SENT 记录本次发布确认。
     *
     * @param id
     *                   固定事件 ID
     * @param token
     *                   本轮认领令牌
     * @param state
     *                   待写入的投递状态
     * @param result
     *                   本次发布结果或原因
     * @param next
     *                   下次可处理 Unix 毫秒
     * @return 实际更新行数；认领令牌或状态条件不匹配时为 0
     */
    int finish(@Param("id") String id, @Param("token") String token, @Param("state") String state,
            @Param("result") String result, @Param("next") long next);

    /**
     * 按请求 ID 将原事件安排为 PENDING，或在尝试预算用尽时记为 QUARANTINED，并保存下次时间、原因及清空令牌。
     * <p>
     * 服务层先持锁核对用户、代次和阶段；清除令牌使发送器的在途回写服从本次重试安排。
     *
     * @param id
     *                        请求 ID，不是事件 ID
     * @param maxAttempts
     *                        失败分支使用的认领尝试预算
     * @param next
     *                        下次可发送 Unix 毫秒
     * @param reason
     *                        暂时性异常原因
     * @return 实际修改行数
     */
    int defer(@Param("id") String id, @Param("maxAttempts") int maxAttempts, @Param("next") long next,
            @Param("reason") String reason);

    /**
     * 按请求 ID 隔离事件并清空令牌，停止自动发送。
     *
     * @param id
     *                   抢券请求 ID
     * @param reason
     *                   重试或隔离原因
     * @return 更新行数
     */
    int quarantine(@Param("id") String id, @Param("reason") String reason);

    /**
     * 按请求 ID 查询事件状态，供核对决定后续动作。
     *
     * @param id
     *               抢券请求 ID
     * @return 事件状态；无记录为 null
     */
    String stateByRequest(@Param("id") String id);

    /**
     * 匹配 SENT 事件，按累计 attempts 与预算安排 PENDING 或 QUARANTINED，并写 next_at_ms、清空令牌。
     * <p>
     * 发送器下一次成功认领时再累计 attempts；更新后应结合事件状态判断是否进入待发队列。
     *
     * @param id
     *                        请求 ID，不是事件 ID
     * @param maxAttempts
     *                        发送认领尝试预算，来自 DeliveryProperties
     * @param now
     *                        本次可重发 Unix 毫秒
     * @return 更新行数；当前状态不是 SENT 或事件缺失时为 0
     */
    int scheduleReplay(@Param("id") String id, @Param("maxAttempts") int maxAttempts, @Param("now") long now);

    /**
     * 将原事件记为 DONE、清空认领令牌并保存原因，使其退出普通投递扫描。
     * <p>
     * 关闭服务在同一事务中将请求记为 CLOSED；在途交付抵达消费者后，由账本守卫读取关闭事实并拒绝建单。
     *
     * @param id
     *                   需要结束投递跟踪的请求 ID
     * @param result
     *                   完成投递跟踪的原因，例如 REQUEST_CLOSED
     * @return 实际更新行数
     */
    int completeRequest(@Param("id") String id, @Param("result") String result);
}

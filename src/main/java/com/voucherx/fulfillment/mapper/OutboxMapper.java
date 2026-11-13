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
     * 用事件 ID 及到期/过期条件竞争认领；成功时写 CLAIMED、新令牌、租约截止，并把 attempts 加 1。
     * <p>
     * 不保证网络发送只执行一次，也不再次关联检查活动/请求，发送器和建单守卫各有后续校验。
     * 本方法不检查尝试预算；租约接手不等于已经终止原持有者。
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
     * 仅在 event_id、claim_token、本状态 CLAIMED 同时匹配时更新发布结果、next_at_ms 并清空令牌。
     * <p>
     * 不检查租约截止是否仍在未来；令牌被接手者或消费 defer 等操作清空/更换后，旧发送器无法覆盖新结果。
     * 写 SENT 只记录发布确认，不将请求改为 SUCCEEDED。
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
     * @return 实际更新行数；旧令牌等条件不匹配时为 0
     */
    int finish(@Param("id") String id, @Param("token") String token, @Param("state") String state,
            @Param("result") String result, @Param("next") long next);

    /**
     * 按请求 ID 将原事件写为 PENDING 或尝试预算已用尽的 QUARANTINED，保存下次时间/原因并清空令牌。
     * <p>
     * SQL 不要求旧状态是 SENT，也不自校验用户或 epoch；服务层已持锁核对。
     * 消费失败可能早于发送器回写 SENT，这里清掉令牌后可阻止其旧 finish 覆盖已安排的重试。
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
     * 仅将 SENT 事件安排为立即重发，尝试次数用尽时改为隔离。
     *
     * @param id
     *                        抢券请求 ID
     * @param maxAttempts
     *                        允许的最大发布尝试次数
     * @param now
     *                        当前 Unix 毫秒
     * @return 更新行数；非 SENT 不修改
     */
    int scheduleReplay(@Param("id") String id, @Param("maxAttempts") int maxAttempts, @Param("now") long now);

    /**
     * 按请求 ID 把事件标为 DONE 并清空令牌，用于结束投递跟踪。
     *
     * @param id
     *                   抢券请求 ID
     * @param result
     *                   发布结果或完成原因
     * @return 更新行数
     */
    int completeRequest(@Param("id") String id, @Param("result") String result);
}

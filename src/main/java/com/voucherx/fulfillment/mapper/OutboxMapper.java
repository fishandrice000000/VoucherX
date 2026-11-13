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
     *
     * @return 插入行数
     */
    int insert(@Param("eventId") String eventId, @Param("requestId") String requestId, @Param("now") long now);

    /**
     * 扫描 NORMAL、代次匹配、请求 ACCEPTED 的事件；包含到期 PENDING 和租约过期 CLAIMED。
     *
     * @param now
     *                  当前 Unix 毫秒
     * @param limit
     *                  最多扫描条数
     *
     * @return 最多 limit 个事件 ID
     */
    List<String> dueEvents(@Param("now") long now, @Param("limit") int limit);

    /**
     * 按到期或租约过期条件竞争认领，写新令牌和租约并增加 attempts。
     *
     * @param id
     *                       Outbox 事件 ID
     * @param token
     *                       本次认领随机令牌
     * @param leaseUntil
     *                       租约截止 Unix 毫秒
     * @param now
     *                       当前 Unix 毫秒
     *
     * @return 认领成功为 1；竞争未命中为 0
     */
    int claim(@Param("id") String id, @Param("token") String token, @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    /**
     * 读取已认领事件关联的请求 ID 和尝试次数；本查询不校验令牌。
     *
     * @param id
     *               Outbox 事件 ID
     *
     * @return 事件摘要；不存在为 null
     */
    OutboxClaim findClaim(@Param("id") String id);

    /**
     * 仅匹配事件 ID、本次 claim_token 且状态 CLAIMED 时回写，防止旧任务覆盖新认领结果。
     *
     * @param id
     *                   Outbox 事件 ID
     * @param token
     *                   本次认领随机令牌
     * @param state
     *                   待写入的投递状态
     * @param result
     *                   发布结果或完成原因
     * @param next
     *                   下次发送 Unix 毫秒
     *
     * @return 更新为 1；已失去回写资格等情况为 0
     */
    int finish(@Param("id") String id, @Param("token") String token, @Param("state") String state,
            @Param("result") String result, @Param("next") long next);

    /**
     * 按请求 ID 将事件重新安排为 PENDING 或次数用尽后的 QUARANTINED，并清空认领令牌。
     *
     * @param id
     *                        抢券请求 ID
     * @param maxAttempts
     *                        允许的最大发布尝试次数
     * @param next
     *                        下次发送 Unix 毫秒
     * @param reason
     *                        重试或隔离原因
     *
     * @return 更新行数
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
     *
     * @return 更新行数
     */
    int quarantine(@Param("id") String id, @Param("reason") String reason);

    /**
     * 按请求 ID 查询事件状态，供核对决定后续动作。
     *
     * @param id
     *               抢券请求 ID
     *
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
     *
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
     *
     * @return 更新行数
     */
    int completeRequest(@Param("id") String id, @Param("result") String result);
}

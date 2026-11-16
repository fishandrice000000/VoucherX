package com.voucherx.recovery.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.recovery.RecoveryService.Control;
import com.voucherx.recovery.RecoveryPlan;
import com.voucherx.recovery.RecoveredOrder;
import com.voucherx.fulfillment.RequestRecord;

/**
 * 活动冻结恢复的持久化操作，SQL 位于 resources/mapper/RecoveryMapper.xml。
 * <p>
 * RecoveryService 在活动控制行独占锁内扫描请求和订单、绑定恢复代次、保存额度计划并推进阶段。
 * Mapper 的写入前置条件由该服务校验，事务由服务管理；返回更新计数表示当前事务内 SQL 的执行结果。
 *
 * @see com.voucherx.recovery.RecoveryService
 */
@Mapper
public interface RecoveryMapper {
    /**
     * 无锁读取控制行；用于状态展示及阶段调度，不能作为后续写操作的锁保护。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 控制快照；控制行不存在时为 null
     */
    Control findControl(@Param("voucher") long voucher);

    /**
     * 以 FOR UPDATE 独占锁定活动控制行，与正常发行的 FOR SHARE 互斥。
     * <p>
     * 锁随服务层事务结束而释放；请求、订单及库存事实应在取得该锁后读取。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 独占锁下的控制快照；不存在时为 null
     */
    Control lockControl(@Param("voucher") long voucher);

    /**
     * 检查秒杀活动是否存在，避免为不存在的活动创建冻结任务。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 匹配活动行数，正常为 0 或 1
     */
    long countActivity(@Param("voucher") long voucher);

    /**
     * 以 INSERT IGNORE 补建活动控制行，使用表默认 NORMAL/epoch=1；已有行保持原状态。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 新插入的行数，已存在时为 0
     */
    int ensureControl(@Param("voucher") long voucher);

    /**
     * 将活动控制行设为 FROZEN，保存目标 epoch、恢复 ID 和原因；调用方须持有活动独占锁并确认状态为 NORMAL。
     *
     * @param voucher
     *                    活动 ID
     * @param epoch
     *                    新恢复代次
     * @param id
     *                    新恢复任务 ID
     * @param reason
     *                    冻结原因
     *
     * @return 更新计数
     */
    int freeze(@Param("voucher") long voucher, @Param("epoch") long epoch, @Param("id") String id,
            @Param("reason") String reason);

    /**
     * 插入 FROZEN 恢复任务，与活动冻结和审计处于同一数据库事务。
     *
     * @param id
     *                    恢复任务 ID
     * @param voucher
     *                    活动 ID
     * @param epoch
     *                    新恢复代次
     * @param now
     *                    创建及更新时间，Unix 毫秒
     *
     * @return 插入行数
     */
    int insertRun(@Param("id") String id, @Param("voucher") long voucher, @Param("epoch") long epoch,
            @Param("now") long now);

    /**
     * 按请求、活动、用户三者统计对应订单，不过滤付款或核销状态。
     * <p>
     * 计划阶段据此要求 SUCCEEDED 有一单、其他请求阶段没有订单；该查询不核对消息 ID 或库存布局。
     *
     * @param request
     *                    请求 ID
     * @param voucher
     *                    活动 ID
     * @param user
     *                    用户 ID
     *
     * @return 对应订单数
     */
    long countMatchingOrders(@Param("request") String request, @Param("voucher") long voucher,
            @Param("user") long user);

    /**
     * 将已核验的请求阶段、预留状态和原因绑定到目标 epoch，保留 requestId 和 eventId；调用方须持有活动独占锁。
     *
     * @param request
     *                        原请求 ID
     * @param phase
     *                        归一化后的请求阶段
     * @param reservation
     *                        归一化后的预留状态
     * @param reason
     *                        保留的原因或恢复判定原因
     * @param epoch
     *                        当前恢复代次
     * @param now
     *                        更新时间，Unix 毫秒
     *
     * @return 更新计数
     */
    int rebindRequest(@Param("request") String request, @Param("phase") String phase,
            @Param("reservation") String reservation, @Param("reason") String reason, @Param("epoch") long epoch,
            @Param("now") long now);

    /**
     * 为有效受理请求插入或重置同一 eventId 的 Outbox，使其进入 PENDING。
     * <p>
     * 冲突更新清零投递次数、清除认领令牌和租约并设为立即可投递；发送时由当前账本构造消息。
     *
     * @param event
     *                    请求原有事件 ID
     * @param request
     *                    请求 ID
     * @param now
     *                    下次可投递时间，Unix 毫秒
     *
     * @return MySQL 插入或冲突更新计数，不能固定理解为 1
     */
    int rebindEvent(@Param("event") String event, @Param("request") String request, @Param("now") long now);

    /**
     * 将非 ACCEPTED 请求的 Outbox 置为 DONE，清除认领令牌并记录 RECOVERY_TERMINAL。
     *
     * @param request
     *                    请求 ID
     *
     * @return 更新计数；没有对应事件时可为 0
     */
    int completeEvent(@Param("request") String request);

    /**
     * 统计同活动中存在多份 ACCEPTED 请求的用户组数。
     * <p>
     * 返回的是冲突用户组数量，不是所有重复请求行数；计划阶段要求为零。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 重复占用的用户组数
     */
    long duplicateReservations(@Param("voucher") long voucher);

    /**
     * 统计 ACCEPTED 请求与同活动、同用户已有订单的冲突连接行数。
     * <p>
     * 已发行与在途不能重复占用同一用户资格；订单不按支付或核销状态过滤。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 请求与订单的冲突连接行数
     */
    long conflictingReservations(@Param("voucher") long voucher);

    /**
     * 保存可用、在途和已发行额度，并把恢复任务记为 RECONCILING。
     * <p>
     * 活动控制行的对应阶段由服务层 transition 一起更新；这里只更新恢复任务，不更改库存。
     *
     * @param id
     *                 恢复任务 ID
     * @param plan
     *                 已通过守恒校验的 A/R/C
     * @param now
     *                 更新时间，Unix 毫秒
     *
     * @return 更新计数
     */
    int savePlan(@Param("id") String id, @Param("plan") RecoveryPlan plan, @Param("now") long now);

    /**
     * 统计活动全部购买订单，即恢复计划中的 C；订单后续支付、取消及用券状态均保持该发行占用。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 已发行订单数
     */
    long countConfirmed(@Param("voucher") long voucher);

    /**
     * 统计 phase=ACCEPTED 的请求，即恢复计划中的 R。
     * <p>
     * 不是对 reservation=RESERVED 的所有记录计数；计划阶段已先验证 ACCEPTED 必须具备 RESERVED 事实。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 已受理且尚未建单的请求数
     */
    long countReserved(@Param("voucher") long voucher);

    /**
     * 按订单 ID 升序游标扫描全部发行订单，重建用户资格及归属。
     * <p>
     * 查询不包含交易状态筛选，也不会锁定每个订单；发行事实的稳定性由外层活动独占锁保障。
     *
     * @param voucher
     *                    活动 ID
     * @param after
     *                    上一页末尾订单 ID，首次为 0；查询严格大于该值
     * @param limit
     *                    本页最大条数
     *
     * @return 按 ID 升序的订单投影；末页之后为空列表
     */
    List<RecoveredOrder> pageOrders(@Param("voucher") long voucher, @Param("after") long after,
            @Param("limit") int limit);

    /**
     * Redis 全量重建发布后，将 CLOSED 且仍为 RESERVED 的请求标记为 RELEASED。
     * <p>
     * 关闭请求未计入新库存计划的 R，额度已在全量重建中体现；这里只收尾数据库状态，不执行 Redis 增量返还。
     *
     * @param voucher
     *                    活动 ID
     * @param now
     *                    更新时间，Unix 毫秒
     *
     * @return 更新计数
     */
    int releaseClosedRequests(@Param("voucher") long voucher, @Param("now") long now);

    /**
     * 将已关闭且预留已释放请求的补偿任务同步为 RELEASED。
     * <p>
     * 调用前已完成 Redis 重建及 releaseClosedRequests；更新范围为符合请求条件的所有补偿任务。
     *
     * @param voucher
     *                    活动 ID
     * @param now
     *                    更新时间，Unix 毫秒
     *
     * @return 更新计数
     */
    int releaseCompensations(@Param("voucher") long voucher, @Param("now") long now);

    /**
     * 检查 ACCEPTED 请求是否都绑定当前 epoch 且存在 PENDING Outbox。
     * <p>
     * 任一代次不符、事件缺失或事件非 PENDING 都计入；这是复流前的投递安排检查，不验证消息负载的全部字段。
     *
     * @param voucher
     *                    活动 ID
     * @param epoch
     *                    当前恢复代次
     *
     * @return 未满足恢复投递条件的受理请求数，复流要求为零
     */
    long countUnmatchedEvents(@Param("voucher") long voucher, @Param("epoch") long epoch);

    /**
     * 按活动 ID 和 epoch 恢复 NORMAL 并清空冻结原因，保留 recoveryId；服务须持独占锁并完成 REBUILDING 校验。
     *
     * @param voucher
     *                    活动 ID
     * @param epoch
     *                    当前恢复代次，防止修改其他代次
     *
     * @return 更新计数
     */
    int resume(@Param("voucher") long voucher, @Param("epoch") long epoch);

    /**
     * 更新指定恢复任务的阶段和时间；阶段前置检查、活动行同步及事务由服务层负责。
     *
     * @param id
     *                  恢复任务 ID
     * @param state
     *                  RECONCILING、REBUILDING 或 DONE 等目标阶段
     * @param now
     *                  更新时间，Unix 毫秒
     *
     * @return 更新计数
     */
    int setRunState(@Param("id") String id, @Param("state") String state, @Param("now") long now);

    /**
     * 读取已保存的 A/R/C；只在计划阶段成功完成后作为重建与复流基准使用。
     *
     * @param id
     *               恢复任务 ID
     *
     * @return 额度计划；任务不存在时为 null
     */
    RecoveryPlan savedPlan(@Param("id") String id);

    /**
     * 按活动 ID 和 epoch 推进恢复阶段；服务持独占锁核验当前阶段，并在同一事务内更新恢复任务状态。
     *
     * @param voucher
     *                    活动 ID
     * @param epoch
     *                    当前恢复代次
     * @param state
     *                    目标活动状态
     *
     * @return 更新计数
     */
    int setActivityState(@Param("voucher") long voucher, @Param("epoch") long epoch, @Param("state") String state);

    /**
     * 按 requestId 升序游标扫描活动全部阶段的请求，复用请求账本的类型化映射。
     * <p>
     * 计划阶段用于事实归一化，重建阶段用于请求 Hash 回填；每次遍历从头开始，无分页检查点。
     *
     * @param voucher
     *                    活动 ID
     * @param after
     *                    上一页末尾 requestId，首次为空串；查询严格大于该值
     * @param limit
     *                    本页最大条数
     *
     * @return 按 requestId 升序的请求记录；遍历结束时为空列表
     */
    List<RequestRecord> pageRequests(@Param("voucher") long voucher, @Param("after") String after,
            @Param("limit") int limit);
}

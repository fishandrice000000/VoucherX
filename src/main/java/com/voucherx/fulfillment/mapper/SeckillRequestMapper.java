package com.voucherx.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.fulfillment.RequestRecord;

/**
 * 映射抢券请求账本；普通查询、请求行锁和阶段变更在此与 SQL 对照。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface SeckillRequestMapper {
    /**
     * 普通读取请求，不加行锁，也不校验用户归属。
     *
     * @param id
     *               请求 ID
     * @return 请求快照；不存在为 null
     */
    RequestRecord find(@Param("id") String id);

    /**
     * 按用户及客户端幂等键查询原请求；同一用户下键唯一。
     *
     * @param userId
     *                   用户 ID
     * @param key
     *                   该用户的幂等键
     * @return 原请求；不存在为 null
     */
    RequestRecord byKey(@Param("userId") long userId, @Param("key") String key);

    /**
     * FOR UPDATE 锁请求行；调用者须先取得活动锁并保持事务。
     *
     * @param id
     *               请求 ID
     * @return 当前请求；不存在为 null
     */
    RequestRecord lock(@Param("id") String id);

    /**
     * 插入意图及固定身份、代次、布局；初始 phase 和 reservation 使用表默认值 INTENT/UNKNOWN。
     *
     * @param id
     *                    请求 ID
     * @param eventId
     *                    固定 Outbox 事件 ID
     * @param user
     *                    用户 ID
     * @param voucher
     *                    活动 ID
     * @param key
     *                    该用户的幂等键
     * @param epoch
     *                    请求绑定的恢复代次
     * @param mode
     *                    SINGLE 或 BUCKET
     * @param bucket
     *                    计划起始桶
     * @param layout
     *                    布局版本
     * @param now
     *                    当前 Unix 毫秒
     * @return 插入行数
     */
    int insertIntent(@Param("id") String id, @Param("eventId") String eventId, @Param("user") long user,
            @Param("voucher") long voucher, @Param("key") String key, @Param("epoch") long epoch,
            @Param("mode") String mode, @Param("bucket") int bucket, @Param("layout") int layout,
            @Param("now") long now);

    /**
     * 写 ACCEPTED/RESERVED；SQL 的 WHERE 仅匹配请求 ID，阶段判断由 RequestLedger.accept
     * 在持有请求行锁时完成。
     * <p>
     * 不能脱离服务层把此 Mapper 当成自带 INTENT 条件的状态机。
     * 同事务中还必须插入 Outbox，避免只有受理事实却没有待发事件。
     *
     * @param id
     *                已持锁且确认仍为 INTENT 的请求 ID
     * @param now
     *                更新 Unix 毫秒
     * @return 实际更新行数
     */
    int accept(@Param("id") String id, @Param("now") long now);

    /**
     * 仅将 ACCEPTED 请求改为 SUCCEEDED 并关联订单，参与建单事务。
     *
     * @param id
     *                    请求 ID
     * @param orderId
     *                    购买订单 ID
     * @param now
     *                    当前 Unix 毫秒
     * @return 更新行数；阶段不匹配时为 0
     */
    int succeed(@Param("id") String id, @Param("orderId") long orderId, @Param("now") long now);

    /**
     * 写为 CLOSED/NONE；仅用于明确未发生 Redis 预留，阶段检查由持锁服务完成。
     *
     * @param id
     *                   请求 ID
     * @param reason
     *                   关闭或诊断原因
     * @param now
     *                   当前 Unix 毫秒
     * @return 更新行数
     */
    int rejectUnreserved(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /**
     * 按请求 ID 写 CLOSED、原因和业务更新时间，保留原 reservation 以便后续释放。
     * <p>
     * SQL 不自带旧阶段或无订单条件，调用者须持活动/请求锁检查允许关闭；补偿 prepare 还须同事务保存补偿意图和完成 Outbox。
     * 只调用本 Mapper 不等于已建立完整补偿协议，更不表示 Redis 已返还。
     *
     * @param id
     *                   已持锁核验可关闭的请求 ID
     * @param reason
     *                   关闭原因
     * @param now
     *                   更新 Unix 毫秒
     * @return 实际更新行数
     */
    int close(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /**
     * 只为仍处于 ACCEPTED 的请求更新诊断原因，不改变阶段。
     *
     * @param id
     *                   请求 ID
     * @param reason
     *                   关闭或诊断原因
     * @param now
     *                   当前 Unix 毫秒
     * @return 更新行数
     */
    int recordReason(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /**
     * 统计尚处于 INTENT/ACCEPTED，或预留仍为 RESERVED/UNKNOWN 的请求，供安全初始化判断。
     *
     * @param voucherId
     *                      活动 ID
     * @return 有未结业务或预留证据的请求数
     */
    long countUnsettled(@Param("voucherId") long voucherId);

    /**
     * 扫描 next_check_at_ms 已到且 updated_at_ms 不晚于观察阈值的请求，按检查时间和请求 ID 排序。
     * <p>
     * 不按 phase 或 reservation 过滤，成功、意图、受理中和关闭都可被检查；陈旧时间不是请求失败事实。
     * 这条普通查询不持有处理资格，后续先 claimCheck，再由业务事务复核。
     *
     * @param now
     *                        本轮扫描 Unix 毫秒
     * @param staleBefore
     *                        允许检查的最晚业务更新时间，Unix 毫秒
     * @param limit
     *                        候选上限
     * @return 待检查的请求 ID 列表
     */
    java.util.List<String> dueForCheck(@Param("now") long now, @Param("staleBefore") long staleBefore,
            @Param("limit") int limit);

    /**
     * 仅在下次检查时间已到时，将它推迟到 next，协调多个扫描者竞争当前窗口。
     * <p>
     * 不写处理令牌或业务终态，也没有跨后续操作持有的租约；不能据此认为未来绝不重复扫描，实际修复仍需锁和幂等。
     *
     * @param id
     *                 请求 ID
     * @param next
     *                 下一检查 Unix 毫秒
     * @param now
     *                 本轮判断检查是否到期的 Unix 毫秒
     * @return 成功条件更新为 1，当前窗口已被推进等情况为 0
     */
    int claimCheck(@Param("id") String id, @Param("next") long next, @Param("now") long now);

    /**
     * 只补记 reservation=RELEASED 及更新时间，不改请求 CLOSED 阶段、不执行 Redis INCR。
     * <p>
     * SQL 按请求 ID 更新，阶段和归属条件由持锁补偿服务保护；须在 Redis 已返回本次/此前释放结果后，与补偿进度和审计同事务保存。
     *
     * @param id
     *                已确认达到释放结果的请求 ID
     * @param now
     *                更新 Unix 毫秒
     * @return 实际更新行数
     */
    int markReleased(@Param("id") String id, @Param("now") long now);
}

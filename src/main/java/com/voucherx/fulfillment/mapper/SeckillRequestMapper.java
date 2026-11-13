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
     *
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
     *
     * @return 原请求；不存在为 null
     */
    RequestRecord byKey(@Param("userId") long userId, @Param("key") String key);

    /**
     * FOR UPDATE 锁请求行；调用者须先取得活动锁并保持事务。
     *
     * @param id
     *               请求 ID
     *
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
     *
     * @return 插入行数
     */
    int insertIntent(@Param("id") String id, @Param("eventId") String eventId, @Param("user") long user,
            @Param("voucher") long voucher, @Param("key") String key, @Param("epoch") long epoch,
            @Param("mode") String mode, @Param("bucket") int bucket, @Param("layout") int layout,
            @Param("now") long now);

    /**
     * 写为 ACCEPTED/RESERVED；SQL 本身不限制原阶段，RequestLedger 必须持锁确认仍为 INTENT。
     *
     * @param id
     *                请求 ID
     * @param now
     *                当前 Unix 毫秒
     *
     * @return 更新行数
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
     *
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
     *
     * @return 更新行数
     */
    int rejectUnreserved(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /**
     * 记录业务关闭及原因，保留原预留状态供后续补偿；SQL 本身不检查原阶段。
     *
     * @param id
     *                   请求 ID
     * @param reason
     *                   关闭或诊断原因
     * @param now
     *                   当前 Unix 毫秒
     *
     * @return 更新行数
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
     *
     * @return 更新行数
     */
    int recordReason(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /**
     * 统计尚处于 INTENT/ACCEPTED，或预留仍为 RESERVED/UNKNOWN 的请求，供安全初始化判断。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 有未结业务或预留证据的请求数
     */
    long countUnsettled(@Param("voucherId") long voucherId);

    /**
     * 按核对时间和更新陈旧阈值扫描有限请求 ID；筛选条件不只包含未成功请求。
     *
     * @param now
     *                        当前 Unix 毫秒
     * @param staleBefore
     *                        只扫描更新时刻不晚于该 Unix 毫秒的请求
     * @param limit
     *                        本轮最多返回条数
     *
     * @return 待核对请求 ID 列表
     */
    java.util.List<String> dueForCheck(@Param("now") long now, @Param("staleBefore") long staleBefore,
            @Param("limit") int limit);

    /**
     * 仅当核对时间已到才推迟下次检查时间，用条件更新协调扫描竞争。
     *
     * @param id
     *                 请求 ID
     * @param next
     *                 下一次核对的 Unix 毫秒
     * @param now
     *                 当前 Unix 毫秒
     *
     * @return 成功认领为 1，竞争未命中为 0
     */
    int claimCheck(@Param("id") String id, @Param("next") long next, @Param("now") long now);

    /**
     * 记录 Redis 释放证据已核验为 RELEASED；方法自身不执行 Redis 脚本。
     *
     * @param id
     *                请求 ID
     * @param now
     *                当前 Unix 毫秒
     *
     * @return 更新行数
     */
    int markReleased(@Param("id") String id, @Param("now") long now);
}

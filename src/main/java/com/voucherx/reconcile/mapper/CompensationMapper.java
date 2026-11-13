package com.voucherx.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.reconcile.CompensationRecord;

/**
 * 补偿意图及执行进度的 MyBatis 访问，SQL 位于同名 XML。
 * <p>
 * request_id 为主键，compensation_id 另有唯一约束；主键防止每次重试生成另一条补偿业务。
 * Mapper 不独立开启事务，关闭屏障、锁顺序及当前代次由 CompensationService 保护。
 *
 * @see com.voucherx.reconcile.CompensationService
 */
@Mapper
public interface CompensationMapper {
    /**
     * 按 requestId 统计购买订单，辅助禁止对已成功业务返还发行额度。
     * <p>
     * 普通查询本身不阻止未来建单；必须配合请求行锁和消费者守卫形成关闭协议。
     *
     * @param id
     *               原业务请求 ID
     * @return 该请求对应订单数
     */
    long countOrders(@Param("id") String id);

    /**
     * 用 INSERT IGNORE 保存首次补偿编号、原因和时间；初始 PENDING/attempts=0 来自表默认值，重复主键时保留原记录。
     *
     * @param id
     *                           原请求 ID，也是补偿表主键
     * @param compensationId
     *                           本次候选追踪编号，仅新记录采用
     * @param now
     *                           首次可执行及更新时间，Unix 毫秒
     * @param reason
     *                           首次意图原因
     * @return 插入行数；已有请求意图通常为 0，不表示原状态已被重置
     */
    int insertIfAbsent(@Param("id") String id, @Param("compensationId") String compensationId, @Param("now") long now,
            @Param("reason") String reason);

    /**
     * FOR UPDATE 按请求 ID 锁补偿记录；调用方已按顺序锁活动及请求，并让事务覆盖 Redis 释放及结果补记。
     *
     * @param id
     *               原请求 ID
     * @return 进度快照；不存在为 null
     */
    CompensationRecord lock(@Param("id") String id);

    /**
     * 按请求 ID 写入服务层计算的状态、次数及下次时间；SQL 不自带 PENDING/旧次数/代次条件。
     * <p>
     * 次数是直接赋值，调用方必须持补偿行锁再由原 attempts 计算加一，并与请求释放标记及审计共同提交。
     *
     * @param id
     *                     原请求 ID
     * @param outcome
     *                     PENDING、RELEASED 或 ISOLATED
     * @param attempts
     *                     本次要保存的新累计次数
     * @param next
     *                     下次可执行 Unix 毫秒
     * @param now
     *                     结果更新 Unix 毫秒
     * @return 更新行数
     */
    int recordResult(@Param("id") String id, @Param("outcome") String outcome, @Param("attempts") int attempts,
            @Param("next") long next, @Param("now") long now);

    /**
     * 普通查询到期 PENDING 补偿，按 next_at_ms 排序并限制条数；不认领或锁行，execute 还须加锁复核。
     *
     * @param now
     *                  本轮扫描 Unix 毫秒
     * @param limit
     *                  候选上限
     * @return 原请求 ID 列表，不是 compensationId 列表
     */
    List<String> due(@Param("now") long now, @Param("limit") int limit);
}

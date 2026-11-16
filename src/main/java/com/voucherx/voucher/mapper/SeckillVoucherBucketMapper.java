package com.voucherx.voucher.mapper;

import com.voucherx.voucher.entity.SeckillVoucherBucket;
import com.voucherx.voucher.model.BucketLayoutStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * BUCKET 分支的最终扣减及聚合 SQL；SINGLE 不访问这些桶库存行。
 * <p>
 * Redis 只做活动级总额度预留，本接口在 MySQL 事务中确定实际扣减桶。
 * 桶号在同一活动下由主键保证唯一，布局版本与正库存是每次扣减必须同时匹配的条件。
 *
 * @see com.voucherx.order.service.PurchaseOrderCreationService
 * @see com.voucherx.seckill.service.SeckillStockInitializer
 * @see BucketLayoutStats
 */
@Mapper
public interface SeckillVoucherBucketMapper {

    /**
     * 条件扣减指定活动、桶号和布局版本的一份余额，仅 remaining_stock 大于 0 才更新。
     * <p>
     * 方法不自动换桶；返回 0 后是否继续尝试由订单服务决定。调用方需已有建单事务，
     * 成功取得的库存行锁持续到事务结束，插单失败时本条更新也回滚。
     *
     * @param voucherId
     *                          活动 ID
     * @param bucketNo
     *                          本次尝试的实际候选桶编号
     * @param layoutVersion
     *                          本请求要求的布局版本
     * @return 成功扣减为 1；桶缺失、版本不符或库存不足为 0
     */
    @Update("""
            UPDATE tb_seckill_voucher_bucket
               SET remaining_stock = remaining_stock - 1
             WHERE voucher_id = #{voucherId}
               AND bucket_no = #{bucketNo}
               AND layout_version = #{layoutVersion}
               AND remaining_stock > 0
            """)
    int deductBucket(@Param("voucherId") Long voucherId,
            @Param("bucketNo") Integer bucketNo,
            @Param("layoutVersion") Integer layoutVersion);

    /**
     * 统计指定活动和版本的桶行数、最小及最大桶号。
     * <p>
     * 将结果交给 covers，结合活动内桶号唯一约束核对完整编号覆盖。
     *
     * @param voucherId
     *                          活动 ID
     * @param layoutVersion
     *                          要核对的布局版本
     * @return 聚合统计；无桶时计数为 0，最小/最大编号为空
     * @see BucketLayoutStats#covers(int)
     */
    @Select("""
            SELECT COUNT(*) AS bucket_count,
                   MIN(bucket_no) AS min_bucket_no,
                   MAX(bucket_no) AS max_bucket_no
              FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    BucketLayoutStats selectLayoutStats(@Param("voucherId") Long voucherId,
            @Param("layoutVersion") Integer layoutVersion);

    /**
     * 统计指定活动及布局版本的桶行数，供布局核对使用。
     *
     * @param voucherId
     *                          活动 ID
     * @param layoutVersion
     *                          库存布局版本
     * @return 桶行数
     */
    @Select("""
            SELECT COUNT(*) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    int countBuckets(@Param("voucherId") Long voucherId,
            @Param("layoutVersion") Integer layoutVersion);

    /**
     * 汇总指定布局的剩余库存，无记录时按 0 计算。
     *
     * @param voucherId
     *                          活动 ID
     * @param layoutVersion
     *                          库存布局版本
     * @return 剩余库存总量
     */
    @Select("""
            SELECT COALESCE(SUM(remaining_stock), 0) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    long sumRemaining(@Param("voucherId") Long voucherId,
            @Param("layoutVersion") Integer layoutVersion);

    /**
     * 汇总指定布局的初始库存，供安全初始化核对总量。
     *
     * @param voucherId
     *                          活动 ID
     * @param layoutVersion
     *                          库存布局版本
     * @return 初始库存总量
     */
    @Select("""
            SELECT COALESCE(SUM(total_stock), 0) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    long sumTotal(@Param("voucherId") Long voucherId,
            @Param("layoutVersion") Integer layoutVersion);
}

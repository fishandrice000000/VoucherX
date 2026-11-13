package com.voucherx.voucher.mapper;

import com.voucherx.voucher.entity.SeckillVoucherBucket;
import com.voucherx.voucher.model.BucketLayoutStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * BUCKET 库存分支的 SQL；入门默认 SINGLE 可先跳过，切换桶模式时与建单和初始化器对照。
 */
@Mapper
public interface SeckillVoucherBucketMapper {

    /**
     * 仅对指定活动、桶及布局版本且剩余库存大于 0 的一行扣减，参与建单事务。
     *
     * @param voucherId
     *                          活动 ID
     * @param bucketNo
     *                          要尝试的桶编号
     * @param layoutVersion
     *                          库存布局版本
     *
     * @return 成功为 1；桶缺失或无库存为 0
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
     * 汇总桶数量及编号范围，供调用方确认是否连续覆盖所需布局。
     *
     * @param voucherId
     *                          活动 ID
     * @param layoutVersion
     *                          库存布局版本
     *
     * @return 桶数量和最小/最大编号统计
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
     * 统计指定活动布局的桶行数；仅数量不能单独证明编号连续。
     *
     * @param voucherId
     *                          活动 ID
     * @param layoutVersion
     *                          库存布局版本
     *
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
     *
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
     *
     * @return 初始库存总量
     */
    @Select("""
            SELECT COALESCE(SUM(total_stock), 0) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    long sumTotal(@Param("voucherId") Long voucherId,
            @Param("layoutVersion") Integer layoutVersion);
}

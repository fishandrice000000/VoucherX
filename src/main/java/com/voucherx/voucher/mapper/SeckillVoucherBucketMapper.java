package com.voucherx.voucher.mapper;

import com.voucherx.voucher.entity.SeckillVoucherBucket;
import com.voucherx.voucher.model.BucketLayoutStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillVoucherBucketMapper {

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

    @Select("""
            SELECT COUNT(*) AS bucket_count,
                   MIN(bucket_no) AS min_bucket_no,
                   MAX(bucket_no) AS max_bucket_no
              FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    BucketLayoutStats selectLayoutStats(@Param("voucherId") Long voucherId,
                                        @Param("layoutVersion") Integer layoutVersion);

    @Select("""
            SELECT COUNT(*) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    int countBuckets(@Param("voucherId") Long voucherId,
                     @Param("layoutVersion") Integer layoutVersion);

    @Select("""
            SELECT COALESCE(SUM(remaining_stock), 0) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    long sumRemaining(@Param("voucherId") Long voucherId,
                      @Param("layoutVersion") Integer layoutVersion);

    @Select("""
            SELECT COALESCE(SUM(total_stock), 0) FROM tb_seckill_voucher_bucket
             WHERE voucher_id = #{voucherId} AND layout_version = #{layoutVersion}
            """)
    long sumTotal(@Param("voucherId") Long voucherId,
                  @Param("layoutVersion") Integer layoutVersion);
}

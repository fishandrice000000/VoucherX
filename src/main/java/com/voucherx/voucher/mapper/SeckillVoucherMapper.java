package com.voucherx.voucher.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.voucher.entity.SeckillVoucher;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * MySQL 条件扣库存。即使 Redis 出现异常，数据库也不会把库存扣成负数。
     */
    @Update("""
            UPDATE tb_seckill_voucher
               SET stock = stock - 1
             WHERE voucher_id = #{voucherId}
               AND stock > 0
            """)
    int deductStock(@Param("voucherId") Long voucherId);
}

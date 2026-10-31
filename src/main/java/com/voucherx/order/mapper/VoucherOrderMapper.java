package com.voucherx.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.order.entity.VoucherOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    @Select("SELECT * FROM tb_voucher_order WHERE request_id = #{requestId} LIMIT 1")
    VoucherOrder selectByRequestId(@Param("requestId") String requestId);

    @Select("""
            SELECT * FROM tb_voucher_order
             WHERE voucher_id = #{voucherId} AND user_id = #{userId}
             LIMIT 1
            """)
    VoucherOrder selectByVoucherAndUser(@Param("voucherId") Long voucherId,
                                        @Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = #{voucherId}")
    long countByVoucherId(@Param("voucherId") Long voucherId);
}

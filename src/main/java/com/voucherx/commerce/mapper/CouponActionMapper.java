package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CouponActionMapper {
    int insert(@Param("coupon") long coupon, @Param("order") long order, @Param("token") String token,
            @Param("action") String action, @Param("now") long now);
}

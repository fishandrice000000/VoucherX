package com.voucherx.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.fulfillment.ActivityGate.Control;

@Mapper
public interface ActivityControlMapper {
    long count(@Param("voucherId") long voucherId);

    int insertIfAbsent(@Param("voucherId") long voucherId);

    Control lockShared(@Param("voucherId") long voucherId);

    boolean isNormal(@Param("voucherId") long voucherId, @Param("epoch") long epoch);

    long countFrozen(@Param("voucherId") long voucherId);
}

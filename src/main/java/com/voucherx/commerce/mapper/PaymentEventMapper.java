package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.commerce.model.PaymentEvent;

@Mapper
public interface PaymentEventMapper {
    PaymentEvent find(@Param("id") String id);

    int insert(@Param("event") PaymentEvent event);
}

package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.ConsumptionOrder;
import com.voucherx.commerce.model.GeneratedId;

@Mapper
public interface ConsumptionOrderMapper {
    int insert(@Param("order") ConsumptionOrder order, @Param("key") GeneratedId key);

    ConsumptionOrder find(@Param("id") long id);

    ConsumptionOrder lock(@Param("id") long id);

    int markPaid(@Param("id") long id);

    int cancel(@Param("id") long id);

    List<Long> expiredIds(@Param("now") long now, @Param("limit") int limit);
}

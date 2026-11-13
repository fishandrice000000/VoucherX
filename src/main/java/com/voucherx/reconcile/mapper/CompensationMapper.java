package com.voucherx.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.reconcile.CompensationRecord;

@Mapper
public interface CompensationMapper {
    long countOrders(@Param("id") String id);

    int insertIfAbsent(@Param("id") String id, @Param("compensationId") String compensationId, @Param("now") long now,
            @Param("reason") String reason);

    CompensationRecord lock(@Param("id") String id);

    int recordResult(@Param("id") String id, @Param("outcome") String outcome, @Param("attempts") int attempts,
            @Param("next") long next, @Param("now") long now);

    List<String> due(@Param("now") long now, @Param("limit") int limit);
}

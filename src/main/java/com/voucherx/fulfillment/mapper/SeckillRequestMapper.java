package com.voucherx.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.fulfillment.RequestRecord;

@Mapper
public interface SeckillRequestMapper {
    RequestRecord find(@Param("id") String id);

    RequestRecord byKey(@Param("userId") long userId, @Param("key") String key);

    RequestRecord lock(@Param("id") String id);

    int insertIntent(@Param("id") String id, @Param("eventId") String eventId, @Param("user") long user,
            @Param("voucher") long voucher, @Param("key") String key, @Param("epoch") long epoch,
            @Param("mode") String mode, @Param("bucket") int bucket, @Param("layout") int layout,
            @Param("now") long now);

    int accept(@Param("id") String id, @Param("now") long now);

    int succeed(@Param("id") String id, @Param("orderId") long orderId, @Param("now") long now);

    int rejectUnreserved(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    int close(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    int recordReason(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    long countUnsettled(@Param("voucherId") long voucherId);

    java.util.List<String> dueForCheck(@Param("now") long now, @Param("staleBefore") long staleBefore,
            @Param("limit") int limit);

    int claimCheck(@Param("id") String id, @Param("next") long next, @Param("now") long now);

    int markReleased(@Param("id") String id, @Param("now") long now);
}

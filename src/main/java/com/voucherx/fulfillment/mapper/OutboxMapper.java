package com.voucherx.fulfillment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.fulfillment.OutboxClaim;

@Mapper
public interface OutboxMapper {
    int insert(@Param("eventId") String eventId, @Param("requestId") String requestId, @Param("now") long now);

    List<String> dueEvents(@Param("now") long now, @Param("limit") int limit);

    int claim(@Param("id") String id, @Param("token") String token, @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    OutboxClaim findClaim(@Param("id") String id);

    int finish(@Param("id") String id, @Param("token") String token, @Param("state") String state,
            @Param("result") String result, @Param("next") long next);

    int defer(@Param("id") String id, @Param("maxAttempts") int maxAttempts, @Param("next") long next,
            @Param("reason") String reason);

    int quarantine(@Param("id") String id, @Param("reason") String reason);

    String stateByRequest(@Param("id") String id);

    int scheduleReplay(@Param("id") String id, @Param("maxAttempts") int maxAttempts, @Param("now") long now);

    int completeRequest(@Param("id") String id, @Param("result") String result);
}

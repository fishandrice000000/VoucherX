package com.voucherx.recovery.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.recovery.RecoveryService.Control;
import com.voucherx.recovery.RecoveryPlan;
import com.voucherx.recovery.RecoveredOrder;
import com.voucherx.fulfillment.RequestRecord;

@Mapper
public interface RecoveryMapper {
    Control findControl(@Param("voucher") long voucher);

    Control lockControl(@Param("voucher") long voucher);

    long countActivity(@Param("voucher") long voucher);

    int ensureControl(@Param("voucher") long voucher);

    int freeze(@Param("voucher") long voucher, @Param("epoch") long epoch, @Param("id") String id,
            @Param("reason") String reason);

    int insertRun(@Param("id") String id, @Param("voucher") long voucher, @Param("epoch") long epoch,
            @Param("now") long now);

    long countMatchingOrders(@Param("request") String request, @Param("voucher") long voucher,
            @Param("user") long user);

    int rebindRequest(@Param("request") String request, @Param("phase") String phase,
            @Param("reservation") String reservation, @Param("reason") String reason, @Param("epoch") long epoch,
            @Param("now") long now);

    int rebindEvent(@Param("event") String event, @Param("request") String request, @Param("now") long now);

    int completeEvent(@Param("request") String request);

    long duplicateReservations(@Param("voucher") long voucher);

    long conflictingReservations(@Param("voucher") long voucher);

    int savePlan(@Param("id") String id, @Param("plan") RecoveryPlan plan, @Param("now") long now);

    long countConfirmed(@Param("voucher") long voucher);

    long countReserved(@Param("voucher") long voucher);

    List<RecoveredOrder> pageOrders(@Param("voucher") long voucher, @Param("after") long after,
            @Param("limit") int limit);

    int releaseClosedRequests(@Param("voucher") long voucher, @Param("now") long now);

    int releaseCompensations(@Param("voucher") long voucher, @Param("now") long now);

    long countUnmatchedEvents(@Param("voucher") long voucher, @Param("epoch") long epoch);

    int resume(@Param("voucher") long voucher, @Param("epoch") long epoch);

    int setRunState(@Param("id") String id, @Param("state") String state, @Param("now") long now);

    RecoveryPlan savedPlan(@Param("id") String id);

    int setActivityState(@Param("voucher") long voucher, @Param("epoch") long epoch, @Param("state") String state);

    List<RequestRecord> pageRequests(@Param("voucher") long voucher, @Param("after") String after,
            @Param("limit") int limit);
}

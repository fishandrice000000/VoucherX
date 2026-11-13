package com.voucherx.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RepairAuditMapper {
    int insert(@Param("request") String request, @Param("voucher") long voucher, @Param("action") String action,
            @Param("evidence") String evidence, @Param("outcome") String outcome, @Param("now") long now);
}

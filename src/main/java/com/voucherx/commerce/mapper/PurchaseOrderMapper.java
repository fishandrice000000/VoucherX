package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.PurchaseOrder;
import com.voucherx.commerce.model.VoucherTerms;

@Mapper
public interface PurchaseOrderMapper {
    VoucherTerms terms(@Param("id") long id);

    int initialize(@Param("id") long id, @Param("terms") VoucherTerms terms, @Param("deadline") long deadline);

    PurchaseOrder find(@Param("id") long id);

    PurchaseOrder lock(@Param("id") long id);

    int markPaid(@Param("id") long id);

    int cancel(@Param("id") long id);

    List<Long> expiredIds(@Param("now") long now, @Param("limit") int limit);
}

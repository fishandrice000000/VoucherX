package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.UserCoupon;
import com.voucherx.commerce.model.PurchaseOrder;

@Mapper
public interface UserCouponMapper {
    UserCoupon find(@Param("id") long id);

    UserCoupon lock(@Param("id") long id);

    List<UserCoupon> page(@Param("user") long user, @Param("after") long after, @Param("limit") int limit);

    int tryLock(@Param("id") long id, @Param("orderId") long orderId, @Param("token") String token,
            @Param("version") long version);

    int issue(@Param("order") PurchaseOrder order, @Param("now") long now, @Param("until") long until);

    int use(@Param("id") long id);

    int release(@Param("id") long id, @Param("state") String state);

    int expireAvailable(@Param("now") long now, @Param("limit") int limit);
}

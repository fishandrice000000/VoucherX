package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 券的 LOCK、USE、RELEASE 动作流水，与对应交易事务一起保存。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface CouponActionMapper {
    /**
     * 写入用户券、消费单、锁令牌和动作；调用者提供事务及归属校验。
     *
     * @param coupon
     *                   用户券 ID
     * @param order
     *                   消费订单 ID
     * @param token
     *                   本轮券锁令牌
     * @param action
     *                   LOCK、USE 或 RELEASE
     * @param now
     *                   动作时刻，Unix 毫秒
     *
     * @return 插入行数
     */
    int insert(@Param("coupon") long coupon, @Param("order") long order, @Param("token") String token,
            @Param("action") String action, @Param("now") long now);
}

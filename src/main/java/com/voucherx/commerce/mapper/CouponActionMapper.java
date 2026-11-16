package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户券 LOCK、USE、RELEASE 的动作流水，在各自交易事务中同步写入。
 * <p>
 * 唯一约束 (coupon_id, lock_token, action)
 * 限制同一轮锁的同种动作重复记录；同一张券重新锁定会使用新令牌。
 * 动作表用于追溯，不代表异步 MQ 投递，也不代替券表当前状态。
 *
 * @see com.voucherx.commerce.CommerceService
 */
@Mapper
public interface CouponActionMapper {
    /**
     * 把用户券、消费单、当轮令牌、动作和时刻保存；用户及锁归属已由服务层校验，调用者保持事务。
     *
     * @param coupon
     *                   用户券 ID
     * @param order
     *                   消费订单 ID
     * @param token
     *                   当轮消费锁令牌
     * @param action
     *                   LOCK、USE 或 RELEASE
     * @param now
     *                   动作 Unix 毫秒
     * @return 插入行数；同券/令牌/动作重复会由唯一约束拒绝
     */
    int insert(@Param("coupon") long coupon, @Param("order") long order, @Param("token") String token,
            @Param("action") String action, @Param("now") long now);
}

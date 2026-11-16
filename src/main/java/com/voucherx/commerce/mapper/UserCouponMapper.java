package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.UserCoupon;
import com.voucherx.commerce.model.PurchaseOrder;

/**
 * 访问用户券的发行、锁定、核销和释放数据，映射 tb_user_coupon。
 * <p>
 * tryLock 具有状态与 version 条件，use 具有 LOCKED 条件，release 按券 ID 更新。
 * 交易服务负责订单锁、券锁、用户归属和令牌校验，事务覆盖完整检查及写入过程。
 *
 * @see com.voucherx.commerce.CommerceService
 * @see com.voucherx.commerce.model.UserCoupon
 */
@Mapper
public interface UserCouponMapper {
    /**
     * 普通查询用户券，不检查当前调用用户是否为券主人。
     *
     * @param id
     *               用户券 ID
     * @return 用户券；不存在为 null
     */
    UserCoupon find(@Param("id") long id);

    /**
     * FOR UPDATE 锁券行；与订单联合操作时由服务层先锁订单再锁券。
     *
     * @param id
     *               用户券 ID
     * @return 用户券；不存在为 null
     */
    UserCoupon lock(@Param("id") long id);

    /**
     * 按用户过滤，查询 id 大于游标的券并按 id 递增返回。
     *
     * @param user
     *                  券所属用户 ID
     * @param after
     *                  上一页最后的券 ID；首页 0
     * @param limit
     *                  本轮最多查询或更新条数
     * @return 最多 limit 张用户券
     */
    List<UserCoupon> page(@Param("user") long user, @Param("after") long after, @Param("limit") int limit);

    /**
     * 只在当前 AVAILABLE 且 version 等于读出值时写 LOCKED，绑定消费单及令牌，version 加一。
     * <p>
     * 本 SQL 不重新校验用户、商户、有效期或门槛，这些由消费服务先持锁验证。
     * 受影响行数必须是 1 才能继续提交；后续 LOCK 动作与新消费单共用事务。
     *
     * @param id
     *                    用户券 ID
     * @param orderId
     *                    本次新消费单 ID
     * @param token
     *                    服务端为这次消费生成的随机锁令牌
     * @param version
     *                    锁券行查询返回的当前版本
     * @return 1 为锁定成功，0 为状态/版本不匹配或券不存在
     */
    int tryLock(@Param("id") long id, @Param("orderId") long orderId, @Param("token") String token,
            @Param("version") long version);

    /**
     * 按购买单快照插入一张用户券，初始 AVAILABLE、版本 0 及空锁字段由表默认值提供。
     * <p>
     * purchase_order_id 的 uk_coupon_source 唯一约束兜底重复来源；这条 SQL 不判断订单 PAID，
     * 因为服务层在同一事务内先插券再改付款状态。必须与订单及支付事件一起提交。
     *
     * @param order
     *                  已持锁且付款可应用的购买单权益快照
     * @param now
     *                  券生效 Unix 毫秒
     * @param until
     *                  券失效 Unix 毫秒
     * @return 插入行数；来源唯一约束冲突会抛异常
     */
    int issue(@Param("order") PurchaseOrder order, @Param("now") long now, @Param("until") long until);

    /**
     * 将 LOCKED 券更新为 USED 并增加 version，保留消费归属字段用于追溯。
     * <p>
     * 服务层已持订单与券行锁核对归属和有效期；该更新与消费单 PAID、USE 动作及支付事件共同提交。
     *
     * @param id
     *               已核验本次锁归属的用户券 ID
     * @return 更新行数；状态不为 LOCKED 或不存在时为 0
     */
    int use(@Param("id") long id);

    /**
     * 按券 ID 写 AVAILABLE 或 EXPIRED，清空锁订单 ID 和令牌，并增加 version。
     * <p>
     * 关闭流程先持订单与券行锁并通过 requireLock；释放结果由券的有效期确定。此操作释放消费使用权。
     *
     * @param id
     *                  已核验当前消费单归属的用户券 ID
     * @param state
     *                  依失效时间计算出的 AVAILABLE 或 EXPIRED
     * @return 更新行数
     */
    int release(@Param("id") long id, @Param("state") String state);

    /**
     * 仅批量更新 valid_until_ms 已到且仍 AVAILABLE 的券为 EXPIRED，version 加一；不直接释放 LOCKED 券。
     *
     * @param now
     *                  本轮到期判断 Unix 毫秒
     * @param limit
     *                  本条 SQL 最多更新的券数
     * @return 本轮实际标记过期的券数
     */
    int expireAvailable(@Param("now") long now, @Param("limit") int limit);
}

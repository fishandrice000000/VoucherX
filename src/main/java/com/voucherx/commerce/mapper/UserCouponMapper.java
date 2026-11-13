package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.UserCoupon;
import com.voucherx.commerce.model.PurchaseOrder;

/**
 * 用户持有的券及其使用状态；锁归属由消费订单 ID 和随机令牌共同表达。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface UserCouponMapper {
    /**
     * 普通查询用户券，不检查当前调用用户是否为券主人。
     *
     * @param id
     *               用户券 ID
     *
     * @return 用户券；不存在为 null
     */
    UserCoupon find(@Param("id") long id);

    /**
     * FOR UPDATE 锁券行；与订单联合操作时由服务层先锁订单再锁券。
     *
     * @param id
     *               用户券 ID
     *
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
     *
     * @return 最多 limit 张用户券
     */
    List<UserCoupon> page(@Param("user") long user, @Param("after") long after, @Param("limit") int limit);

    /**
     * 仅当 AVAILABLE 且 version 匹配时更新为 LOCKED，绑定消费单及令牌并增加版本。
     *
     * @param id
     *                    用户券 ID
     * @param orderId
     *                    锁券的消费订单 ID
     * @param token
     *                    本轮锁券随机令牌
     * @param version
     *                    读取时的乐观锁版本
     *
     * @return 锁定成功为 1，竞争或状态不匹配为 0
     */
    int tryLock(@Param("id") long id, @Param("orderId") long orderId, @Param("token") String token,
            @Param("version") long version);

    /**
     * 按购买订单快照插入 AVAILABLE 用户券；购买订单唯一约束防止重复发券。
     *
     * @param order
     *                  包含权益快照的购买订单
     * @param now
     *                  当前 Unix 毫秒；发券时为有效期起点
     * @param until
     *                  用户券失效 Unix 毫秒
     *
     * @return 插入行数
     */
    int issue(@Param("order") PurchaseOrder order, @Param("now") long now, @Param("until") long until);

    /**
     * 仅将 LOCKED 券改为 USED 并增加版本；用户及锁归属已由持锁服务校验，SQL 不再次比较令牌。
     *
     * @param id
     *               用户券 ID
     *
     * @return 更新行数
     */
    int use(@Param("id") long id);

    /**
     * 清空锁单 ID 和令牌，写 AVAILABLE/EXPIRED 并增加版本；SQL 不比较旧状态和令牌，服务必须先持锁核对归属。
     *
     * @param id
     *                  用户券 ID
     * @param state
     *                  释放后状态 AVAILABLE 或 EXPIRED
     *
     * @return 更新行数
     */
    int release(@Param("id") long id, @Param("state") String state);

    /**
     * 有限更新已到失效时刻且仍 AVAILABLE 的券为 EXPIRED，不直接修改 LOCKED 券。
     *
     * @param now
     *                  当前 Unix 毫秒；发券时为有效期起点
     * @param limit
     *                  本轮最多查询或更新条数
     *
     * @return 本轮过期的券数
     */
    int expireAvailable(@Param("now") long now, @Param("limit") int limit);
}

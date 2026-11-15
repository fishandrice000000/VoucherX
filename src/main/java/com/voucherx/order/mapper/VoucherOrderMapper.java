package com.voucherx.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.order.entity.VoucherOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 秒杀购买订单的基础 Mapper，映射 tb_voucher_order。
 * <p>
 * insert 等基础方法由 MyBatis-Plus BaseMapper 提供；自增主键回填到 VoucherOrder.id。
 * 本接口读取建单身份，付款和权益快照的完整视图另见 PurchaseOrderMapper。
 *
 * @see com.voucherx.commerce.mapper.PurchaseOrderMapper
 */
@Mapper
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 按请求唯一身份查订单，供重复消息幂等和对账核对使用。
     *
     * @param requestId
     *                      抢券请求 ID
     *
     * @return 购买订单；不存在为 null
     */
    @Select("SELECT * FROM tb_voucher_order WHERE request_id = #{requestId} LIMIT 1")
    VoucherOrder selectByRequestId(@Param("requestId") String requestId);

    /**
     * 提前检查该用户是否已有活动订单，减少无意义扣库存；最终仍以数据库唯一约束为准。
     *
     * @param voucherId
     *                      活动 ID
     * @param userId
     *                      用户 ID
     *
     * @return 已有购买订单；不存在为 null
     */
    @Select("""
            SELECT * FROM tb_voucher_order
             WHERE voucher_id = #{voucherId} AND user_id = #{userId}
             LIMIT 1
            """)
    VoucherOrder selectByVoucherAndUser(@Param("voucherId") Long voucherId,
            @Param("userId") Long userId);

    /**
     * 统计活动已有订单；只要有历史订单，Redis 库存丢失就不能按初始总量自动重建。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 该活动历史订单数
     */
    @Select("SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = #{voucherId}")
    long countByVoucherId(@Param("voucherId") Long voucherId);
}

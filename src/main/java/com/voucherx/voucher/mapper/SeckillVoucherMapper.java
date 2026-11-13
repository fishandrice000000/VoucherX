package com.voucherx.voucher.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.voucher.entity.SeckillVoucher;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动 Mapper；基础查询由 MyBatis-Plus 提供，SINGLE 模式的最终扣库存使用下方条件 SQL。
 */
@Mapper
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

    /**
     * 在 stock 大于 0 时原子扣一份 MySQL 单库存，防止扣成负数。
     * <p>
     * 调用者检查受影响行数，并让这条更新与建单、请求成功状态在同一事务提交或回滚。
     *
     * @param voucherId
     *                      活动 ID
     *
     * @return 扣减成功为 1；活动不存在或库存不足为 0
     */
    @Update("""
            UPDATE tb_seckill_voucher
               SET stock = stock - 1
             WHERE voucher_id = #{voucherId}
               AND stock > 0
            """)
    int deductStock(@Param("voucherId") Long voucherId);
}

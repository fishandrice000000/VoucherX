package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.commerce.model.PaymentEvent;

/**
 * 模拟支付事件表访问，event_id 是全表主键，而非单订单内局部唯一键。
 * <p>
 * 一条记录保存输入声明和处理后的历史回执；同事件重放要比较类型、订单、用户、金额和 success。
 * 订单行锁仅协调同一订单，跨订单事件 ID 冲突另由主键兜底；事务和冲突处理由服务层负责。
 *
 * @see com.voucherx.commerce.CommerceService#simulate
 * @see PaymentEvent
 */
@Mapper
public interface PaymentEventMapper {
    /**
     * 按全局 eventId 普通读取已记录的输入和回执，不重新执行状态规则；没有记录返回 null。
     *
     * @param id
     *               支付事件幂等键
     * @return 原事件快照；不是订单当前状态查询
     */
    PaymentEvent find(@Param("id") String id);

    /**
     * 保存本事件的输入、outcome 和当时 orderState；与本次订单变更及发券/核销/关闭共用交易事务。
     *
     * @param event
     *                  服务层已经判定完处理结果的事件
     * @return 插入行数；事件主键冲突抛异常，不能覆盖旧记录
     */
    int insert(@Param("event") PaymentEvent event);
}

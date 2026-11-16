package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.commerce.model.PaymentEvent;

/**
 * 访问模拟支付事件及其首次处理回执，event_id 为全表主键。
 * <p>
 * 重放时核对类型、订单、用户、金额和 success；订单行锁协调同单操作，事件主键协调跨订单碰撞。
 * 事务和冲突处理由交易服务管理。
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
     * @return 插入行数；事件主键冲突抛异常，不能覆盖既有事件记录
     */
    int insert(@Param("event") PaymentEvent event);
}

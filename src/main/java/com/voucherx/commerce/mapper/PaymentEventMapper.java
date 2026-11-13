package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.voucherx.commerce.model.PaymentEvent;

/**
 * 支付事件表访问；eventId 唯一，用于重放相同事件并检测内容冲突。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface PaymentEventMapper {
    /**
     * 按全局事件 ID 查原始处理回执；冲突字段比较由服务层完成。
     *
     * @param id
     *               支付事件 ID
     *
     * @return 支付事件快照；不存在为 null
     */
    PaymentEvent find(@Param("id") String id);

    /**
     * 记录付款声明、处理结果及当时订单状态，与发券/核销及订单变更共用服务事务。
     *
     * @param event
     *                  已经判定处理结果的支付事件
     *
     * @return 插入行数
     */
    int insert(@Param("event") PaymentEvent event);
}

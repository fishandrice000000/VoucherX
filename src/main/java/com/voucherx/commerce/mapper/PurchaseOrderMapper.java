package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.PurchaseOrder;
import com.voucherx.commerce.model.VoucherTerms;

/**
 * 购买订单交易视图，对 tb_voucher_order 的付款字段和权益快照进行类型化访问。
 * <p>
 * SQL 位于同名 XML；Mapper 不自行开启业务事务。返回更新行数的方法仅描述写入结果，调用者负责所需的状态检查和行锁。
 */
@Mapper
public interface PurchaseOrderMapper {
    /**
     * 从 tb_voucher 读取当前模板规则；只在建单固定快照时使用。
     *
     * @param id
     *               券模板 ID
     *
     * @return 模板规则；不存在为 null
     */
    VoucherTerms terms(@Param("id") long id);

    /**
     * 给刚创建的订单固定金额及权益快照，置为 PENDING_PAYMENT 并设置截止时间。
     *
     * @param id
     *                     购买订单 ID
     * @param terms
     *                     建单时读取的模板规则快照
     * @param deadline
     *                     订单付款截止 Unix 毫秒
     *
     * @return 更新行数
     */
    int initialize(@Param("id") long id, @Param("terms") VoucherTerms terms, @Param("deadline") long deadline);

    /**
     * 普通查询购买单，不锁行也不校验用户归属。
     *
     * @param id
     *               购买订单 ID
     *
     * @return 购买订单；不存在为 null
     */
    PurchaseOrder find(@Param("id") long id);

    /**
     * FOR UPDATE 锁住购买单，串行化付款、取消与超时关闭。
     *
     * @param id
     *               购买订单 ID
     *
     * @return 购买订单；不存在为 null
     */
    PurchaseOrder lock(@Param("id") long id);

    /**
     * 仅将 PENDING_PAYMENT 条件更新为 PAID；发券和支付事件由同一服务事务完成。
     *
     * @param id
     *               购买订单 ID
     *
     * @return 成功转移为 1；状态不匹配为 0
     */
    int markPaid(@Param("id") long id);

    /**
     * 仅将 PENDING_PAYMENT 更新为 CANCELLED，不回补秒杀库存。
     *
     * @param id
     *               购买订单 ID
     *
     * @return 成功转移为 1；状态不匹配为 0
     */
    int cancel(@Param("id") long id);

    /**
     * 按付款截止时间扫描已到期且待付的购买单，不加行锁；关单时还须复核。
     *
     * @param now
     *                  扫描时刻，Unix 毫秒
     * @param limit
     *                  最多返回条数
     *
     * @return 有界候选订单 ID 列表
     */
    List<Long> expiredIds(@Param("now") long now, @Param("limit") int limit);
}

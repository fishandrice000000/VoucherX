package com.voucherx.commerce.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import com.voucherx.commerce.model.PurchaseOrderSnapshot;
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
     * @return 模板规则；不存在为 null
     */
    VoucherTerms terms(@Param("id") long id);

    /**
     * 在新购买单创建事务中按订单 ID 写付款与权益快照，并设置 PENDING_PAYMENT 和截止时间。
     * <p>
     * 调用前提为该购买单正在创建；发券接续成功支付事件处理。
     *
     * @param id
     *                     本次刚创建的购买订单 ID
     * @param terms
     *                     从券模板读取的买价、面额、商户、门槛和有效时长
     * @param deadline
     *                     购买付款截止 Unix 毫秒
     * @return 更新行数
     */
    int initialize(@Param("id") long id, @Param("terms") VoucherTerms terms, @Param("deadline") long deadline);

    /**
     * 普通查询购买单，不锁行也不校验用户归属。
     *
     * @param id
     *               购买订单 ID
     * @return 购买订单；不存在为 null
     */
    PurchaseOrderSnapshot selectById(@Param("id") long id);

    /**
     * FOR UPDATE 锁购买单，付款、主动取消、到期关闭均先取得这行锁；用户归属检查由服务层完成。
     *
     * @param id
     *               购买订单 ID
     * @return 当前购买单快照；不存在为 null，锁由调用者事务保持
     */
    PurchaseOrderSnapshot selectByIdForUpdate(@Param("id") long id);

    /**
     * 将 PENDING_PAYMENT 条件更新为 PAID。
     * <p>
     * 交易服务在同一事务中先发券、检查本次更新数为 1，再保存支付事件，全部共同提交或回滚。
     *
     * @param id
     *               已持锁并允许应用付款的购买订单 ID
     * @return 成功转移为 1，状态不匹配或订单不存在为 0
     */
    int markPaid(@Param("id") long id);

    /**
     * 将待付购买单条件更新为 CANCELLED，保留订单及其活动发行占用。
     *
     * @param id
     *               调用方已持锁的购买订单 ID
     * @return 更新行数；不处于 PENDING_PAYMENT 时为 0
     */
    int cancel(@Param("id") long id);

    /**
     * 按付款截止时间扫描已到期且待付的购买单，不加行锁；关单时还须复核。
     *
     * @param now
     *                  扫描时刻，Unix 毫秒
     * @param limit
     *                  最多返回条数
     * @return 有界候选订单 ID 列表
     */
    List<Long> expiredIds(@Param("now") long now, @Param("limit") int limit);
}

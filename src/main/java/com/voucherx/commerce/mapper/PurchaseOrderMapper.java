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
     * @return 模板规则；不存在为 null
     */
    VoucherTerms terms(@Param("id") long id);

    /**
     * 在新建购买单事务中固定付款和券规则快照，并设置 PENDING_PAYMENT 及截止时刻。
     * <p>
     * SQL 只按 id 更新，不限定原付款状态；只应由新建单流程调用，不能把它当作付款或取消接口。
     * 这里仍不发券，发券要等成功支付事件。
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
    PurchaseOrder find(@Param("id") long id);

    /**
     * FOR UPDATE 锁购买单，付款、主动取消、到期关闭均先取得这行锁；用户归属检查由服务层完成。
     *
     * @param id
     *               购买订单 ID
     * @return 当前购买单快照；不存在为 null，锁由调用者事务保持
     */
    PurchaseOrder lock(@Param("id") long id);

    /**
     * 仅将 PENDING_PAYMENT 条件更新为 PAID，不修改基础建单 status。
     * <p>
     * 服务层在同一事务先发券，再执行此更新并要求影响 1 行，最后保存支付事件；任一步失败一起回滚。
     *
     * @param id
     *               已持锁并允许应用付款的购买订单 ID
     * @return 成功转移为 1，状态不匹配或订单不存在为 0
     */
    int markPaid(@Param("id") long id);

    /**
     * 仅把待付款购买单改为 CANCELLED；不删除订单、不发行用户券，也不修改活动库存或领取资格。
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

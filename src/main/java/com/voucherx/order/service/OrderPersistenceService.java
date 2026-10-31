package com.voucherx.order.service;

import com.voucherx.order.entity.VoucherOrder;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.voucher.entity.SeckillVoucher;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 秒杀订单的 MySQL 事务边界。
 * 条件扣库存与订单插入必须属于同一个事务；任何一步失败都回滚。
 * 该类独立于 RabbitMQ Consumer，便于单独测试事务行为。
 */
@Service
public class OrderPersistenceService {

    private final VoucherOrderMapper orderMapper;
    private final SeckillVoucherMapper seckillVoucherMapper;

    public OrderPersistenceService(VoucherOrderMapper orderMapper,
                                   SeckillVoucherMapper seckillVoucherMapper) {
        this.orderMapper = orderMapper;
        this.seckillVoucherMapper = seckillVoucherMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderPersistResult persist(SeckillOrderMessage message) {
        // 正常重复消费：同一个 requestId 已经成功落库。
        VoucherOrder existing = orderMapper.selectByRequestId(message.requestId());
        if (existing != null) {
            return new OrderPersistResult(existing.getId(), false);
        }

        // 数据库组合唯一约束是最终一人一单保障；这里提前检查，减少无意义的库存扣减。
        VoucherOrder existingUserOrder =
                orderMapper.selectByVoucherAndUser(message.voucherId(), message.userId());
        if (existingUserOrder != null) {
            throw new OrderBusinessException(OrderFailure.DUPLICATE_USER,
                    "该用户已存在同一活动订单");
        }

        SeckillVoucher activity = seckillVoucherMapper.selectById(message.voucherId());
        if (activity == null) {
            throw new OrderBusinessException(OrderFailure.VOUCHER_NOT_FOUND,
                    "秒杀活动不存在");
        }

        // 条件更新防止库存为负；即使 Redis 预扣出现偏差，MySQL 仍是最终防线。
        int changed = seckillVoucherMapper.deductStock(message.voucherId());
        if (changed == 0) {
            throw new OrderBusinessException(OrderFailure.SOLD_OUT, "数据库库存不足");
        }

        VoucherOrder order = new VoucherOrder();
        order.setRequestId(message.requestId());
        order.setMessageId(message.messageId());
        order.setUserId(message.userId());
        order.setVoucherId(message.voucherId());
        order.setStatus(VoucherOrder.STATUS_CREATED);
        // 唯一约束冲突会抛出 DuplicateKeyException，使整个事务（含库存扣减）回滚。
        orderMapper.insert(order);
        return new OrderPersistResult(order.getId(), true);
    }
}

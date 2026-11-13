package com.voucherx.commerce.model;

/**
 * 接收 MyBatis 回填的消费订单自增主键，使 ConsumptionOrder 草稿保持不可变。
 * <p>
 * ConsumptionOrderMapper.xml 配置 useGeneratedKeys 和 keyProperty=key.id；
 * insert 返回值是行数，消费单 ID 要从本对象读取，再写入用户券的 lock_order_id。
 */
public class GeneratedId {
    private Long id;

    /**
     * 读取数据库回填的消费订单主键。
     *
     * @return 自增主键；尚未回填时为 null
     */
    public Long getId() {
        return id;
    }

    /**
     * 由 MyBatis 在插入后回填自增主键。
     *
     * @param id
     *               数据库生成的消费订单 ID
     */
    public void setId(Long id) {
        this.id = id;
    }
}

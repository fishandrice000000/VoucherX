package com.voucherx.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 活动恢复扫描配置，绑定 voucherx.recovery；默认每页 200 条、单次遍历最多 100000 条。
 * <p>
 * pageSize 限制单次查询量，maxRecords 分别限制一次请求遍历或订单扫描的累计数量。
 * 分页期间阶段事务和活动独占锁持续有效。
 *
 * @param pageSize
 *                       每页数量，范围 1～1000
 * @param maxRecords
 *                       单次遍历的记录上限，不小于 pageSize 且不超过 1000000
 */
@ConfigurationProperties("voucherx.recovery")
public record RecoveryProperties(@DefaultValue("200") int pageSize, @DefaultValue("100000") int maxRecords) {
    /**
     * 校验扫描规模，避免无效分页或无限制扫描进入恢复阶段。
     *
     * @param pageSize
     *                       每页数量
     * @param maxRecords
     *                       单次遍历的记录上限
     * @throws IllegalArgumentException
     *                                      分页大小或记录预算越界
     */
    public RecoveryProperties {
        if (pageSize < 1 || pageSize > 1000 || maxRecords < pageSize || maxRecords > 1000000)
            throw new IllegalArgumentException("无效恢复分页/记录预算");
    }
}

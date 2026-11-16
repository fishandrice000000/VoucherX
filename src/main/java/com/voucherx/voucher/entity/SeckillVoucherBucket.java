package com.voucherx.voucher.entity;

import java.time.LocalDateTime;

/**
 * 数据库库存桶的一行字段模型，映射 tb_seckill_voucher_bucket。
 * <p>
 * 主键为活动 ID 与桶号，layout_version 标识该桶所属布局，同活动各桶分别保存余额。
 * 库存扣减和汇总由 SeckillVoucherBucketMapper 的明确 SQL 执行。
 *
 * @see com.voucherx.voucher.mapper.SeckillVoucherBucketMapper
 * @see com.voucherx.voucher.model.BucketLayoutStats
 */
public class SeckillVoucherBucket {

    /**
     * 桶所属活动 ID；所有桶共享该活动的 Redis 总库存键。
     */
    private Long voucherId;
    /**
     * 数据库库存桶编号，从 0 开始。
     */
    private Integer bucketNo;
    /**
     * 本桶初始额度，允许为 0；各桶初始量之和应等于活动总额度。
     */
    private Integer totalStock;
    /**
     * 本桶当前 MySQL 余额；条件更新每次减 1，数据库约束要求处于 0 到本桶初始额度之间。
     */
    private Integer remainingStock;
    /**
     * 该桶所属布局版本；扣减 SQL 同时匹配活动、桶号和布局版本。
     */
    private Integer layoutVersion;
    /**
     * 桶记录的数据库创建时间。
     */
    private LocalDateTime createTime;
    /**
     * 桶记录的数据库更新时间。
     */
    private LocalDateTime updateTime;

    public Long getVoucherId() {
        return voucherId;
    }

    public void setVoucherId(Long voucherId) {
        this.voucherId = voucherId;
    }

    public Integer getBucketNo() {
        return bucketNo;
    }

    public void setBucketNo(Integer bucketNo) {
        this.bucketNo = bucketNo;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }

    public Integer getRemainingStock() {
        return remainingStock;
    }

    public void setRemainingStock(Integer remainingStock) {
        this.remainingStock = remainingStock;
    }

    public Integer getLayoutVersion() {
        return layoutVersion;
    }

    public void setLayoutVersion(Integer layoutVersion) {
        this.layoutVersion = layoutVersion;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}

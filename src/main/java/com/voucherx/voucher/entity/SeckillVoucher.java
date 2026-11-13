package com.voucherx.voucher.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 秒杀活动及库存配置，映射 tb_seckill_voucher；用于入口时间校验、安全初始化和最终建单扣库存。
 */
@TableName("tb_seckill_voucher")
public class SeckillVoucher {

    /**
     * 券模板/活动主键，与模板 tb_voucher.id 对应。
     */
    @TableId
    private Long voucherId;
    /**
     * 活动初始总库存；安全初始化用来判断是否从未消费。
     */
    private Integer totalStock;
    /**
     * SINGLE 模式 MySQL 剩余库存；BUCKET 模式实际余量由桶表汇总。
     */
    private Integer stock;
    /**
     * SINGLE 单行库存或 BUCKET 分桶库存。
     */
    private String stockMode;
    /**
     * 库存桶数量；BUCKET 模式须为正数。
     */
    private Integer bucketCount;
    /**
     * 库存布局版本，用于拒绝布局不匹配的消息。
     */
    private Integer layoutVersion;
    /**
     * 活动开始本地日期时间，入口按系统时区转换为 Unix 毫秒。
     */
    private LocalDateTime beginTime;
    /**
     * 活动结束本地日期时间，入口按系统时区转换为 Unix 毫秒。
     */
    private LocalDateTime endTime;
    /**
     * 数据库创建时间。
     */
    private LocalDateTime createTime;
    /**
     * 数据库更新时间。
     */
    private LocalDateTime updateTime;

    public Long getVoucherId() {
        return voucherId;
    }

    public void setVoucherId(Long voucherId) {
        this.voucherId = voucherId;
    }

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }

    public String getStockMode() {
        return stockMode;
    }

    public void setStockMode(String stockMode) {
        this.stockMode = stockMode;
    }

    public Integer getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(Integer bucketCount) {
        this.bucketCount = bucketCount;
    }

    public Integer getLayoutVersion() {
        return layoutVersion;
    }

    public void setLayoutVersion(Integer layoutVersion) {
        this.layoutVersion = layoutVersion;
    }

    public LocalDateTime getBeginTime() {
        return beginTime;
    }

    public void setBeginTime(LocalDateTime beginTime) {
        this.beginTime = beginTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
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

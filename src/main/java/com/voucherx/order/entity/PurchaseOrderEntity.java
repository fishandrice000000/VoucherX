package com.voucherx.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 秒杀建单时写入的基础购买订单实体；映射 tb_voucher_order，ID 由数据库自增并由 MyBatis-Plus 回填。
 * <p>
 * 这里只表达请求、库存布局及建单状态；付款状态和权益快照的完整读取模型是 PurchaseOrderSnapshot。
 *
 * @see com.voucherx.commerce.model.PurchaseOrderSnapshot
 */
@TableName("tb_voucher_order")
public class PurchaseOrderEntity {

    /**
     * 基础订单创建成功值 1，不代表已付款。
     */
    public static final int STATUS_CREATED = 1;

    /**
     * 购买订单自增主键；insert 后回填。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 抢券请求 ID，重复消费以此复用原订单。
     */
    private String requestId;
    /**
     * 固定事件 ID，对应 Outbox.eventId。
     */
    private String messageId;
    /**
     * 买券用户 ID。
     */
    private Long userId;
    /**
     * 券模板/秒杀活动 ID。
     */
    private Long voucherId;
    /**
     * 建单库存模式 SINGLE 或 BUCKET。
     */
    private String stockMode;
    /**
     * MySQL 实际扣减成功的桶号，SINGLE 记录 0。
     */
    private Integer bucketNo;
    /**
     * 本次建单使用的库存布局版本。
     */
    private Integer layoutVersion;
    /**
     * 基础建单状态；付款请看独立的 payment_status。
     */
    private Integer status;
    /**
     * 插单前记录的近似落库 Unix 毫秒，略早于事务真正提交。
     */
    private Long completedAtMs;
    /**
     * 数据库创建时间。
     */
    private LocalDateTime createTime;
    /**
     * 数据库更新时间。
     */
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getVoucherId() {
        return voucherId;
    }

    public void setVoucherId(Long voucherId) {
        this.voucherId = voucherId;
    }

    public String getStockMode() {
        return stockMode;
    }

    public void setStockMode(String stockMode) {
        this.stockMode = stockMode;
    }

    public Integer getBucketNo() {
        return bucketNo;
    }

    public void setBucketNo(Integer bucketNo) {
        this.bucketNo = bucketNo;
    }

    public Integer getLayoutVersion() {
        return layoutVersion;
    }

    public void setLayoutVersion(Integer layoutVersion) {
        this.layoutVersion = layoutVersion;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCompletedAtMs() {
        return completedAtMs;
    }

    public void setCompletedAtMs(Long completedAtMs) {
        this.completedAtMs = completedAtMs;
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

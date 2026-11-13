package com.voucherx.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 商户详情实体，映射 tb_shop；既可从 MySQL 回源读取，也可从 Redis JSON 反序列化。
 */
@TableName("tb_shop")
public class Shop {

    /**
     * 商户自增主键，用户券的 shopId 限制可消费商户。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 商户名称。
     */
    private String name;
    /**
     * 商户分类 ID。
     */
    private Long typeId;
    /**
     * 商户展示图片信息。
     */
    private String images;
    /**
     * 商户所在商圈。
     */
    private String area;
    /**
     * 商户地址。
     */
    private String address;
    /**
     * 商户人均价格，单位分；抢券和消费应付金额不从此字段计算。
     */
    private Long avgPrice;
    /**
     * 商户销量展示字段。
     */
    private Integer sold;
    /**
     * 商户评论数量。
     */
    private Integer comments;
    /**
     * 商户评分，放大 10 倍存储。
     */
    private Integer score;
    /**
     * 营业时间展示文本。
     */
    private String openHours;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getTypeId() {
        return typeId;
    }

    public void setTypeId(Long typeId) {
        this.typeId = typeId;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Long getAvgPrice() {
        return avgPrice;
    }

    public void setAvgPrice(Long avgPrice) {
        this.avgPrice = avgPrice;
    }

    public Integer getSold() {
        return sold;
    }

    public void setSold(Integer sold) {
        this.sold = sold;
    }

    public Integer getComments() {
        return comments;
    }

    public void setComments(Integer comments) {
        this.comments = comments;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getOpenHours() {
        return openHours;
    }

    public void setOpenHours(String openHours) {
        this.openHours = openHours;
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

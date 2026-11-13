package com.voucherx.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 登录用户实体，映射 tb_user；首次验证码登录时创建，手机号唯一约束处理并发建档。
 */
@TableName("tb_user")
public class User {

    /**
     * 数据库自增用户 ID，后续写入 Redis Token 映射。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 用户登录手机号，数据库唯一。
     */
    private String phone;
    /**
     * 用户展示昵称，首次创建时使用手机号尾号生成。
     */
    private String nickName;
    /**
     * 用户头像；首次创建为空字符串。
     */
    private String icon;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
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

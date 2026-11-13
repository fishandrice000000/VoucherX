package com.voucherx.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表的 MyBatis-Plus Mapper；AuthService 用继承的查询及插入方法按手机号查找/创建用户。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}

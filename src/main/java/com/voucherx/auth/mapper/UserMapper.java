package com.voucherx.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}

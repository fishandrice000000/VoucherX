package com.voucherx.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.voucherx.shop.entity.Shop;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商户表的 MyBatis-Plus Mapper；缓存支线通过继承的 selectById 回源查询详情。
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {
}

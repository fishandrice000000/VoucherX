package com.voucherx.shop.controller;

import com.voucherx.common.api.ApiResponse;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.shop.entity.Shop;
import com.voucherx.shop.service.ShopQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开的商户详情入口，是入门指南中的缓存查询支线。
 *
 * @see com.voucherx.shop.service.ShopQueryService
 */
@RestController
@RequestMapping("/api/shops")
public class ShopController {

    private final ShopQueryService shopQueryService;

    public ShopController(ShopQueryService shopQueryService) {
        this.shopQueryService = shopQueryService;
    }

    /**
     * 查询商户详情并统一包装响应；服务返回空值时转换为商户不存在的业务错误。
     *
     * @param shopId
     *                   URL 中的商户 ID
     *
     * @return 商户详情
     */
    @GetMapping("/{shopId}")
    public ApiResponse<Shop> getShop(@PathVariable Long shopId) {
        Shop shop = shopQueryService.getShop(shopId);
        if (shop == null) {
            throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
        }
        return ApiResponse.ok(shop);
    }
}

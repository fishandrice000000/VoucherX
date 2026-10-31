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

@RestController
@RequestMapping("/api/shops")
public class ShopController {

    private final ShopQueryService shopQueryService;

    public ShopController(ShopQueryService shopQueryService) {
        this.shopQueryService = shopQueryService;
    }

    @GetMapping("/{shopId}")
    public ApiResponse<Shop> getShop(@PathVariable Long shopId) {
        Shop shop = shopQueryService.getShop(shopId);
        if (shop == null) {
            throw new BusinessException(ErrorCode.SHOP_NOT_FOUND);
        }
        return ApiResponse.ok(shop);
    }
}

package com.voucherx.commerce;

import com.voucherx.auth.context.UserContext;
import com.voucherx.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.List;
import com.voucherx.commerce.model.CommerceOrder;
import com.voucherx.commerce.model.UserCoupon;

@RestController
@RequestMapping("/api")
public class CommerceController {
    private final CommerceService service;

    public CommerceController(CommerceService service) {
        this.service = service;
    }

    public record Consume(@Positive long shopId, @Positive long couponId, @Positive long originalAmount) {
    }

    public record Payment(@NotNull CommerceService.Kind orderKind, @Positive long orderId,
            @NotBlank @Size(max = 64) String eventId, @PositiveOrZero long amount, boolean success) {
    }

    @PostMapping("/consumption-orders")
    public ApiResponse<Map<String, Long>> consume(@Valid @RequestBody Consume body) {
        return ApiResponse.ok(Map.of("orderId",
                service.consume(UserContext.getRequired(), body.shopId(), body.couponId(), body.originalAmount())));
    }

    @PostMapping("/payments/simulate")
    public ApiResponse<CommerceService.Receipt> pay(@Valid @RequestBody Payment body) {
        return ApiResponse.ok(service.simulate(UserContext.getRequired(), body.orderKind(), body.orderId(),
                body.eventId(), body.amount(), body.success()));
    }

    @GetMapping("/orders/{kind}/{id}")
    public ApiResponse<CommerceOrder> order(@PathVariable CommerceService.Kind kind, @PathVariable long id) {
        return ApiResponse.ok(service.order(kind, id, UserContext.getRequired(), false));
    }

    @PostMapping("/orders/{kind}/{id}/cancel")
    public ApiResponse<Map<String, String>> cancel(@PathVariable CommerceService.Kind kind, @PathVariable long id) {
        return ApiResponse.ok(Map.of("state", service.cancel(UserContext.getRequired(), kind, id)));
    }

    @GetMapping("/coupons")
    public ApiResponse<List<UserCoupon>> coupons(@RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.coupons(UserContext.getRequired(), after, size));
    }
}

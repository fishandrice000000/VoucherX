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

/**
 * 交易 HTTP 入口：查询购买/消费订单、模拟付款、查询用户券、创建消费单和取消未付订单。
 * <p>
 * 当前用户统一从认证上下文取得，订单及券的归属在服务层进一步校验。
 * 入门主线按 pay(PURCHASE) → coupons → consume → pay(CONSUMPTION) 阅读。
 */
@RestController
@RequestMapping("/api")
public class CommerceController {
    private final CommerceService service;

    public CommerceController(CommerceService service) {
        this.service = service;
    }

    /**
     * 消费下单请求；此时使用的是购买付款后发给用户的那张券。
     *
     * @param shopId
     *                           消费商户 ID
     * @param couponId
     *                           用户券 ID，不是活动 ID
     * @param originalAmount
     *                           消费原价，单位分，必须为正数
     */
    public record Consume(@Positive long shopId, @Positive long couponId, @Positive long originalAmount) {
    }

    /**
     * 模拟支付事件请求；这是本地演示入口，须显式开启模拟支付配置。
     *
     * @param orderKind
     *                      PURCHASE 购买券或 CONSUMPTION 用券消费
     * @param orderId
     *                      对应类型的订单 ID
     * @param eventId
     *                      支付事件幂等键；重试同一事件须保持内容一致
     * @param amount
     *                      本次支付金额，单位分，必须等于订单应付金额
     * @param success
     *                      本次模拟付款是否成功；false 只记录失败尝试
     */
    public record Payment(@NotNull CommerceService.Kind orderKind, @Positive long orderId,
            @NotBlank @Size(max = 64) String eventId, @PositiveOrZero long amount, boolean success) {
    }

    /**
     * 创建待付消费订单并锁定用户券，返回消费订单 ID；此时券尚未核销。
     *
     * @param body
     *                 商户、用户券和消费原价
     *
     * @return orderId 为新消费订单 ID 的响应
     */
    @PostMapping("/consumption-orders")
    public ApiResponse<Map<String, Long>> consume(@Valid @RequestBody Consume body) {
        return ApiResponse.ok(Map.of("orderId",
                service.consume(UserContext.getRequired(), body.shopId(), body.couponId(), body.originalAmount())));
    }

    /**
     * 按订单类型处理付款：购买付款发券，消费付款核销已锁的券。
     *
     * @param body
     *                 支付事件；身份从当前登录上下文取得
     *
     * @return 事件处理结果及订单付款状态
     */
    @PostMapping("/payments/simulate")
    public ApiResponse<CommerceService.Receipt> pay(@Valid @RequestBody Payment body) {
        return ApiResponse.ok(service.simulate(UserContext.getRequired(), body.orderKind(), body.orderId(),
                body.eventId(), body.amount(), body.success()));
    }

    /**
     * 普通查询当前用户拥有的指定类型订单，不请求行锁。
     *
     * @param kind
     *                 PURCHASE 或 CONSUMPTION
     * @param id
     *                 对应类型的订单 ID
     *
     * @return 包含应付金额及付款状态的订单详情
     */
    @GetMapping("/orders/{kind}/{id}")
    public ApiResponse<CommerceOrder> order(@PathVariable CommerceService.Kind kind, @PathVariable long id) {
        return ApiResponse.ok(service.order(kind, id, UserContext.getRequired(), false));
    }

    /**
     * 取消当前用户的未付订单；消费单关闭还会释放它拥有的券锁。
     *
     * @param kind
     *                 PURCHASE 或 CONSUMPTION
     * @param id
     *                 对应类型的订单 ID
     *
     * @return 最终订单状态，正常为 CANCELLED
     */
    @PostMapping("/orders/{kind}/{id}/cancel")
    public ApiResponse<Map<String, String>> cancel(@PathVariable CommerceService.Kind kind, @PathVariable long id) {
        return ApiResponse.ok(Map.of("state", service.cancel(UserContext.getRequired(), kind, id)));
    }

    /**
     * 按用户券 ID 游标向后查询当前用户的券，不采用页码偏移。
     *
     * @param after
     *                  上一页最后一张券 ID；首页传 0
     * @param size
     *                  每页条数，默认 20，服务层限制为 1 到 100
     *
     * @return 按 ID 递增排列的用户券列表
     */
    @GetMapping("/coupons")
    public ApiResponse<List<UserCoupon>> coupons(@RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.coupons(UserContext.getRequired(), after, size));
    }
}

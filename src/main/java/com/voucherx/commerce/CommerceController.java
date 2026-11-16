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
 * 交易 HTTP 入口，提供购买付款、用户券查询、消费锁券、消费付款核销、取消及订单查询。
 * <p>
 * 用户身份来自认证上下文，服务层核对订单与券归属，并计算优惠、应付和有效期限。
 * SimulatedPaymentRequest 和 PaymentReceipt 使用接口字段命名，订单与用户券查询模型以 snake_case 序列化。
 *
 * @see CommerceService
 * @see com.voucherx.commerce.model.UserCoupon
 */
@RestController
@RequestMapping("/api")
public class CommerceController {
    private final CommerceService service;

    public CommerceController(CommerceService service) {
        this.service = service;
    }

    /**
     * 消费下单输入，couponId 指购买付款后实际发给用户的那张券。
     * <p>
     * 当前没有商品价格目录，originalAmount 是简化场景的输入；服务端验证归属、商户、金额和门槛后自行计算优惠和应付。
     * 锁令牌由服务端生成，不由此请求提供。
     *
     * @param shopId
     *                           消费商户 ID
     * @param couponId
     *                           用户券 ID，不是模板或活动 ID
     * @param originalAmount
     *                           原消费金额，分，必须为正
     */
    public record CreateConsumptionOrderRequest(@Positive long shopId, @Positive long couponId,
            @Positive long originalAmount) {
    }

    /**
     * 接收模拟支付事件，显式启用模拟支付配置后可调用。
     * <p>
     * orderKind 与 orderId 定位业务订单，eventId 标识全局支付事件；同事件重试应保持类型、订单、金额和结果声明一致。
     * PaymentReceipt.outcome 表示该事件的处理分类和权益应用结果。
     *
     * @param orderKind
     *                      PURCHASE 购买券或 CONSUMPTION 用券消费
     * @param orderId
     *                      对应类型的订单 ID
     * @param eventId
     *                      支付事件幂等键，服务层进一步检查字符格式
     * @param amount
     *                      声明支付金额，分，必须等于订单应付，可为 0
     * @param success
     *                      本次模拟成功与否；false 只记录失败尝试
     */
    public record SimulatedPaymentRequest(@NotNull CommerceService.OrderKind orderKind, @Positive long orderId,
            @NotBlank @Size(max = 64) String eventId, @PositiveOrZero long amount, boolean success) {
    }

    /**
     * 创建待付消费单并锁定用户券，返回消费订单 ID；折扣和应付金额在服务端计算，核销留到消费付款。
     *
     * @param body
     *                 商户、用户券及原消费金额；用户从当前登录态取得
     * @return 包含 orderId 的成功响应
     */
    @PostMapping("/consumption-orders")
    public ApiResponse<Map<String, Long>> createConsumptionOrder(
            @Valid @RequestBody CreateConsumptionOrderRequest body) {
        return ApiResponse.ok(Map.of("orderId",
                service.createConsumptionOrder(UserContext.getRequired(), body.shopId(), body.couponId(),
                        body.originalAmount())));
    }

    /**
     * 把已校验的支付输入及当前用户交给事务服务，购买单付款发券，消费单付款核销。
     * <p>
     * 成功包装不等于 outcome=APPLIED：同事件重放、已付款或终态冲突都可能返回业务回执。
     *
     * @param body
     *                 模拟支付事件，金额单位分
     * @return 支付事件回执；orderState 在重放时是原事件保存的快照
     * @see CommerceService#simulatePayment
     */
    @PostMapping("/payments/simulate")
    public ApiResponse<CommerceService.PaymentReceipt> simulatePayment(
            @Valid @RequestBody SimulatedPaymentRequest body) {
        return ApiResponse.ok(service.simulatePayment(UserContext.getRequired(), body.orderKind(), body.orderId(),
                body.eventId(), body.amount(), body.success()));
    }

    /**
     * 普通查询当前用户拥有的指定类型订单，不请求行锁。
     *
     * @param kind
     *                 PURCHASE 或 CONSUMPTION
     * @param id
     *                 对应类型的订单 ID
     * @return 包含应付金额及付款状态的订单详情
     */
    @GetMapping("/orders/{kind}/{id}")
    public ApiResponse<CommerceOrder> getOrder(@PathVariable CommerceService.OrderKind kind, @PathVariable long id) {
        return ApiResponse.ok(service.getOrder(kind, id, UserContext.getRequired()));
    }

    /**
     * 取消当前用户的未付订单；消费单关闭还会释放它拥有的券锁。
     *
     * @param kind
     *                 PURCHASE 或 CONSUMPTION
     * @param id
     *                 对应类型的订单 ID
     * @return 最终订单状态，正常为 CANCELLED
     */
    @PostMapping("/orders/{kind}/{id}/cancel")
    public ApiResponse<Map<String, String>> cancelOrder(@PathVariable CommerceService.OrderKind kind,
            @PathVariable long id) {
        return ApiResponse.ok(Map.of("state", service.cancel(UserContext.getRequired(), kind, id)));
    }

    /**
     * 按用户券 ID 游标向后查询当前用户的券，不采用页码偏移。
     *
     * @param after
     *                  上一页最后一张券 ID；首页传 0
     * @param size
     *                  每页条数，默认 20，服务层限制为 1 到 100
     * @return 按 ID 递增排列的用户券列表
     */
    @GetMapping("/coupons")
    public ApiResponse<List<UserCoupon>> listCoupons(@RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.coupons(UserContext.getRequired(), after, size));
    }
}

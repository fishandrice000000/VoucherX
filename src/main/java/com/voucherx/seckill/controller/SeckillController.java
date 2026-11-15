package com.voucherx.seckill.controller;

import com.voucherx.admission.SeckillAdmissionService;
import com.voucherx.auth.context.UserContext;
import com.voucherx.common.api.ApiResponse;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.dto.SeckillRequestStatusResponse;
import com.voucherx.seckill.service.SeckillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 抢券与结果查询的 HTTP 入口；当前用户由认证拦截器提供。
 * <p>
 * 沿 {@link #seckill} 进入服务编排，再通过 {@link #queryRequest} 观察异步建单结果。
 * HTTP 202 只表示请求进入异步处理协议，最终状态要结合响应中的 status 和后续查询判断。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillAdmissionService admissionService;

    public SeckillController(SeckillService seckillService,
            SeckillAdmissionService admissionService) {
        this.seckillService = seckillService;
        this.admissionService = admissionService;
    }

    /**
     * 先做本地突发保护，再把已认证用户、活动 ID 和必填幂等键交给服务层。
     *
     * @param voucherId
     *                      URL 中的活动 ID
     * @param key
     *                      必填 Idempotency-Key；同一用户重试同一次抢券应复用它
     *
     * @return HTTP 202 响应，包含可查询的请求 ID 及 PROCESSING 或 UNKNOWN 等状态
     */
    @PostMapping("/vouchers/{voucherId}")
    public ResponseEntity<ApiResponse<SeckillAcceptResponse>> seckill(@PathVariable Long voucherId,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key") String key) {
        // 认证拦截器已经执行；本地突发保护在数据库查询/Redis 预扣之前。
        admissionService.acquireLocal();
        SeckillAcceptResponse response = seckillService.seckill(UserContext.getRequired(), voucherId, key);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    /**
     * 查询当前用户拥有的抢券请求；其他用户的请求按不存在处理。
     *
     * @param requestId
     *                      抢券接口返回的请求 ID
     *
     * @return 数据库确认的建单状态及可选订单 ID
     */
    @GetMapping("/requests/{requestId}")
    public ApiResponse<SeckillRequestStatusResponse> queryRequest(@PathVariable String requestId) {
        return ApiResponse.ok(seckillService.queryRequest(UserContext.getRequired(), requestId));
    }
}

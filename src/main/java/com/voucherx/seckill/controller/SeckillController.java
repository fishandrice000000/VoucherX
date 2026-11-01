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

    @PostMapping("/vouchers/{voucherId}")
    public ResponseEntity<ApiResponse<SeckillAcceptResponse>> seckill(@PathVariable Long voucherId) {
        // 认证拦截器已经执行；本地突发保护在数据库查询/Redis 预扣之前。
        admissionService.acquireLocal();
        SeckillAcceptResponse response = seckillService.seckill(UserContext.getRequired(), voucherId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    @GetMapping("/requests/{requestId}")
    public ApiResponse<SeckillRequestStatusResponse> queryRequest(@PathVariable String requestId) {
        return ApiResponse.ok(seckillService.queryRequest(UserContext.getRequired(), requestId));
    }
}

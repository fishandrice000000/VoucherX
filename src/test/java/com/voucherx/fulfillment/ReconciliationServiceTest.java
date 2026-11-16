package com.voucherx.fulfillment;

import com.voucherx.common.redis.RedisKeys;
import com.voucherx.order.mapper.PurchaseOrderEntityMapper;
import com.voucherx.reconcile.*;
import com.voucherx.fulfillment.mapper.OutboxMapper;
import com.voucherx.seckill.service.SeckillRequestStatusCache;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 对账动作回执的单元测试，验证尽力缓存及诊断后状态变化的表达。 */
class ReconciliationServiceTest {
    private final OutboxMapper outbox = mock(OutboxMapper.class);
    private final RequestLedger ledger = mock(RequestLedger.class);
    private final ActivityGate gate = mock(ActivityGate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RepairAudit audit = mock(RepairAudit.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    /** 为固定请求 R1 构造指定阶段的账本快照。 */
    private RequestRecord request(String phase) {
        return new RequestRecord("R1", "E1", 1001, 2001, phase, "RESERVED", 1, "SINGLE", 0, 1,
                "SUCCEEDED".equals(phase) ? 3001L : null, null, 100, 100);
    }

    /** 使用给定诊断观察结果调用真实 repair，缓存服务保留其异常处理逻辑。 */
    private ReconciliationService service(ReconcileDecision action, RequestRecord request) {
        var cache = new SeckillRequestStatusCache(redis, new DefaultRedisScript<Long>());
        var delivery = new DeliveryProperties(true, 32, 30000, 8, 1000, false, 16, 1048576, 50);
        var service = spy(new ReconciliationService(outbox, ledger, gate, mock(PurchaseOrderEntityMapper.class),
                redis, cache, mock(CompensationService.class), audit, transactions,
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC), delivery));
        doReturn(new ReconciliationService.Diagnosis(request, action, "SENT", true, request.orderId()))
                .when(service).diagnose("R1");
        when(gate.normal(2001, 1)).thenReturn(true);
        return service;
    }

    /** Redis 数据访问失败由缓存层记录，对账回执准确表示已尝试同步。 */
    @Test
    void cacheConnectionFailureReturnsSyncAttempted() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("isolated test failure"));
        var service = service(ReconcileDecision.SYNC_RESULT, request("SUCCEEDED"));
        assertThat(service.repair("R1")).isEqualTo("SYNC_ATTEMPTED");
        verify(audit).record(eq("R1"), eq(2001L), eq("SYNC_RESULT"), anyString(), eq("SYNC_ATTEMPTED"));
    }

    /** Lua 返回未更新时，回执仍只声明已尝试，且使用当前请求的事件身份。 */
    @Test
    void cacheDeclinedUpdateReturnsSyncAttempted() {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenReturn(0L);
        var service = service(ReconcileDecision.SYNC_RESULT, request("SUCCEEDED"));
        assertThat(service.repair("R1")).isEqualTo("SYNC_ATTEMPTED");
        verify(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(java.util.List.of(RedisKeys.seckillRequest("R1"))), eq("SUCCESS"), eq("E1"), eq("3001"), eq(""));
    }

    /** 诊断后请求已关闭时，持锁复核返回状态变化并保留事件原投递安排。 */
    @Test
    void closedRequestAfterDiagnosisReturnsStateChanged() {
        var service = service(ReconcileDecision.REPLAY_EVENT, request("ACCEPTED"));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(gate.lockNormalActivityAndGetEpoch(2001)).thenReturn(1L);
        when(ledger.getRequestForUpdate("R1")).thenReturn(request("CLOSED"));
        assertThat(service.repair("R1")).isEqualTo("STATE_CHANGED");
        verifyNoInteractions(outbox);
        verify(transactions).commit(any());
    }
}

package com.voucherx.reconcile;

import com.voucherx.reconcile.mapper.RepairAuditMapper;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 保存修复动作、当时依据和执行标签，供沿请求查证修复过程。
 * <p>
 * 本组件没有事务注解；在 prepare/execute 内调用时加入其事务，repair 顶层动作结束后调用时通常另行写入。
 * 审计记录不替代请求/补偿/Redis 的当前事实，一次修复也可能产生多条审计，不能按行数统计实际返还次数。
 *
 * @see com.voucherx.reconcile.mapper.RepairAuditMapper
 * @see CompensationService
 */
@Component
public class RepairAudit {
    private final RepairAuditMapper mapper;
    private final Clock clock;

    public RepairAudit(RepairAuditMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 按 Java 字符串长度将 evidence 截至最多 1024，附加业务时钟毫秒后交给 Mapper 插入；不自行开启事务，也不吞掉插入异常。
     *
     * @param request
     *                     关联请求 ID；活动级审计可为空
     * @param voucher
     *                     活动 ID
     * @param action
     *                     动作名称，例如 CLOSE_REQUEST 或 RELEASE_RESERVATION
     * @param evidence
     *                     调用方提供的非空依据文本，是当时观察而非全系统一致快照
     * @param outcome
     *                     本次动作标签或结果，需结合对应业务记录判断
     */
    public void record(String request, long voucher, String action, String evidence, String outcome) {
        mapper.insert(request, voucher, action, evidence.substring(0, Math.min(1024, evidence.length())), outcome,
                clock.millis());
    }
}

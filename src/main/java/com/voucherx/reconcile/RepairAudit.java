package com.voucherx.reconcile;

import com.voucherx.reconcile.mapper.RepairAuditMapper;
import org.springframework.stereotype.Component;
import java.time.Clock;

/**
 * 保存修复动作、观察依据、结果标签及时间，供按请求追踪处理过程。
 * <p>
 * 在 closeRequestAndPrepareCompensation、releaseReservationAndRecordResult
 * 中调用时加入对应事务；repair 顶层记录通常独立写入。
 * 一次修复可记录多条审计，实际返还进度以补偿记录及请求预留状态核对。
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

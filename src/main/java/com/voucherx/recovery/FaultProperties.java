package com.voucherx.recovery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 故障注入配置，对应 voucherx.fault，默认禁用。
 * <p>
 * 启用且 point 非空时，FaultInjector 构造器按配置 arm 一次；测试也可在启用状态下直接 arm。
 * 这些配置不会建立对外控制接口，FAIL/PAUSE 也不等于杀进程或网络分区实验。
 *
 * @param enabled
 *                         是否启用注入；默认 false
 * @param point
 *                         启动时预置的 FaultPoint 枚举名，默认空串、不预置
 * @param subject
 *                         主体匹配键，默认 *
 * @param action
 *                         启动动作枚举名，默认 FAIL
 * @param runId
 *                         证据运行标识，默认 manual
 * @param waitMillis
 *                         PAUSE 最长等待毫秒数，默认 5000，范围 1～30000
 * @param evidencePath
 *                         JSONL 证据路径，默认 tmp/fault/evidence.jsonl
 */
@ConfigurationProperties("voucherx.fault")
public record FaultProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String point,
        @DefaultValue("*") String subject, @DefaultValue("FAIL") String action,
        @DefaultValue("manual") String runId, @DefaultValue("5000") long waitMillis,
        @DefaultValue("tmp/fault/evidence.jsonl") String evidencePath) {
    /**
     * 限制暂停等待时间；即使未启用也校验该值。
     * <p>
     * 故障点及动作枚举名在注入器实际按启动配置预置时解析，此处不解析。
     *
     * @param enabled
     *                         是否启用
     * @param point
     *                         启动故障点
     * @param subject
     *                         主体匹配键
     * @param action
     *                         启动动作
     * @param runId
     *                         证据运行标识
     * @param waitMillis
     *                         最长等待毫秒数
     * @param evidencePath
     *                         证据文件路径
     * @throws IllegalArgumentException
     *                                      等待时间不在 1～30000 毫秒内
     */
    public FaultProperties {
        if (waitMillis < 1 || waitMillis > 30000)
            throw new IllegalArgumentException("故障等待上限为 30 秒");
    }
}

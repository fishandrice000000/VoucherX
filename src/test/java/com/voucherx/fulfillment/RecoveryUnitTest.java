package com.voucherx.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voucherx.recovery.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/**
 * 06 的纯单元测试：验证额度计算、故障匹配、到达证据与线程栅栏。
 * <p>
 * 不连接 MySQL、Redis 或 RabbitMQ；组件级恢复及旧消息隔离见 DeliveryComponentTest。
 * 受控异常和线程暂停证明代码路径及交错，不提供真实杀进程或恢复时长的结论。
 */
class RecoveryUnitTest {
    /**
     * JUnit 隔离的临时目录，故障证据只写入此处，避免污染真实运行证据。
     */
    @TempDir
    Path directory;

    /**
     * 用指南的 100/80/20/5 验证 A=75、R=5、C=20，防止把数据库余量直接作为可用额度。
     * <p>
     * 同时验证已全部发行时 A/R 均为零。
     */
    @Test
    void recoveryKeepsConfirmedAndInFlightQuotasSeparate() {
        assertThat(RecoveryPlan.calculate(100, 80, 20, 5)).isEqualTo(new RecoveryPlan(75, 5, 20));
        assertThat(RecoveryPlan.calculate(100, 0, 100, 0)).isEqualTo(new RecoveryPlan(0, 0, 100));
    }

    /**
     * 验证订单数与库存差额不符、在途超额及负库存均拒绝计算，不把矛盾输入带入恢复。
     */
    @Test
    void inconsistentOrOverReservedSnapshotsCannotResume() {
        assertThatThrownBy(() -> RecoveryPlan.calculate(100, 80, 21, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RecoveryPlan.calculate(100, 80, 20, 81)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RecoveryPlan.calculate(100, -1, 101, 0)).isInstanceOf(IllegalStateException.class);
    }

    /**
     * 验证默认禁用时 hit 不创建证据文件，主动 arm 也被拒绝。
     */
    @Test
    void disabledFaultsHaveNoSideEffects() {
        var injector = injector(false);
        injector.hit(FaultPoint.RESERVE_DONE, "r", "e");
        assertThat(directory.resolve("evidence.jsonl")).doesNotExist();
        assertThatThrownBy(() -> injector.arm(FaultPoint.RESERVE_DONE, "r", "test", FaultInjector.Action.FAIL))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 验证其他主体不命中、目标主体只命中一次，且 reached 与一条实际到达证据相对应。
     * <p>
     * 此处测试的是 FAIL 异常，不是进程退出。
     *
     * @throws Exception
     *                       临时证据文件读取失败
     */
    @Test
    void faultIsScopedOneShotAndRecordsActualArrival() throws Exception {
        var injector = injector(true);
        var barrier = injector.arm(FaultPoint.RESERVE_DONE, "r", "run", FaultInjector.Action.FAIL);
        injector.hit(FaultPoint.RESERVE_DONE, "other", "e");
        assertThat(barrier.reached.getCount()).isEqualTo(1);
        assertThatThrownBy(() -> injector.hit(FaultPoint.RESERVE_DONE, "r", "e"))
                .isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(barrier.reached.getCount()).isZero();
        injector.hit(FaultPoint.RESERVE_DONE, "r", "e");
        assertThat(Files.readAllLines(directory.resolve("evidence.jsonl"))).hasSize(1).first().asString()
                .contains("RESERVE_DONE", "run");
    }

    /**
     * 用 reached 确认工作线程到达并处于 PAUSE，再释放 release，验证线程可以继续结束。
     * <p>
     * 等待设置超时以避免测试挂起，不用固定 sleep 猜测线程是否到达，也不测量业务恢复时长。
     *
     * @throws Exception
     *                       栅栏等待、任务执行或完成等待失败
     */
    @Test
    void barrierControlsInterleavingWithoutFixedSleep() throws Exception {
        var injector = injector(true);
        var barrier = injector.arm(FaultPoint.RECOVERY_BEFORE_RESUME, "1", "run", FaultInjector.Action.PAUSE);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> injector.hit(FaultPoint.RECOVERY_BEFORE_RESUME, "1", "e"));
            assertThat(barrier.reached.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(future.isDone()).isFalse();
            barrier.release.countDown();
            future.get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * 构造写入临时目录的注入器；point 为空，测试显式 arm 所需的单次故障。
     *
     * @param enabled
     *                    本测试是否启用故障注入
     *
     * @return 等待上限为 3 秒、使用独立证据文件的注入器
     */
    private FaultInjector injector(boolean enabled) {
        return new FaultInjector(new FaultProperties(enabled, "", "*", "FAIL", "test", 3000,
                directory.resolve("evidence.jsonl").toString()), Clock.systemUTC(), new ObjectMapper());
    }
}

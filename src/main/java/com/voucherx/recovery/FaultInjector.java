package com.voucherx.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 默认关闭的故障注入器，用精确到达点演示重试及线程交错；没有 HTTP 控制入口。
 * <p>
 * 测试先 arm，再等待 Barrier.reached，最后释放 Barrier.release 或观察 FAIL 异常。
 * 匹配键是故障点加 subject，事件 ID 和 runId 仅写入证据；每次配置只消费一次，支持 subject=* 通配。
 * <p>
 * 命中后先追加 JSONL 到达记录，再通知 reached，最后抛异常或暂停。
 * 证据只证明当前进程到达此处，不能证明数据库已提交、进程已被杀死、跨网络故障或恢复时长。
 * PAUSE 会阻塞当前业务线程，并保留调用处尚未结束的事务及锁。
 *
 * @see FaultPoint
 */
@Component
public class FaultInjector {
    /**
     * 命中一次故障点之后执行的动作；它们不会主动终止 JVM。
     */
    public enum Action {
        /**
         * 在写入到达证据后抛出 InjectedFault，是否回滚取决于调用点所在事务。
         */
        FAIL,
        /**
         * 在写入到达证据后等待 release，超时或中断会转为 InjectedFault。
         */
        PAUSE
    }

    /**
     * 受控注入异常，供测试断言或触发所在事务的异常处理；不同于进程崩溃。
     */
    public static class InjectedFault extends RuntimeException {
        /**
         * 构造注入异常，保存故障点或等待失败说明。
         *
         * @param point
         *                  故障点名称或超时、中断说明
         */
        public InjectedFault(String point) {
            super(point);
        }
    }

    /**
     * 单次命中的线程栅栏：到达通知与继续执行许可各有一个 CountDownLatch。
     * <p>
     * reached 只在证据写入成功后放行；PAUSE 等待 release，FAIL 不等待。
     */
    public static class Barrier {
        /**
         * 证据写入后递减为零；测试用 await 确定线程已经到达，无需固定 sleep。
         */
        public final CountDownLatch reached = new CountDownLatch(1);
        /**
         * PAUSE 的继续执行许可；测试或 clear 调用 countDown 解除等待。
         */
        public final CountDownLatch release = new CountDownLatch(1);
        /**
         * 证据中的测试运行标识，不参与故障匹配。
         */
        final String runId;
        /**
         * 此栅栏被消费后执行的 FAIL 或 PAUSE。
         */
        final Action action;

        /**
         * 构造一份尚未到达、尚未释放的单次栅栏。
         *
         * @param runId
         *                   证据运行标识
         * @param action
         *                   命中动作
         */
        Barrier(String runId, Action action) {
            this.runId = runId;
            this.action = action;
        }
    }

    /**
     * 匹配键；同一个故障点可以按请求 ID、活动 ID 等业务主体分别布置。
     *
     * @param point
     *                    故障发生位置
     * @param subject
     *                    具体主体标识或通配符 *
     */
    private record Key(FaultPoint point, String subject) {
    }

    /**
     * 尚未消费的栅栏；精确键优先于通配键，同键再次 arm 会替换同键配置。
     */
    private final ConcurrentHashMap<Key, Barrier> armed = new ConcurrentHashMap<>();
    /**
     * 已消费且仍在执行 hit 的栅栏，供 clear 尝试解除正在等待的线程。
     */
    private final java.util.Set<Barrier> active = ConcurrentHashMap.newKeySet();
    private final FaultProperties config;
    private final Clock clock;
    private final ObjectMapper mapper;

    /**
     * 装配注入器；启用且 point 非空时，按启动配置预置一次故障。
     * <p>
     * point/action 通过枚举 valueOf 解析，名称区分大小写；重新启动进程会根据配置重新预置。
     *
     * @param config
     *                   开关、启动故障及证据配置
     * @param clock
     *                   证据时间来源
     * @param mapper
     *                   JSONL 序列化工具
     * @throws IllegalArgumentException
     *                                      已启用的非空故障点或动作不是有效枚举名
     */
    public FaultInjector(FaultProperties config, Clock clock, ObjectMapper mapper) {
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
        if (config.enabled() && !config.point().isBlank())
            arm(FaultPoint.valueOf(config.point()), config.subject(), config.runId(), Action.valueOf(config.action()));
    }

    /**
     * 为故障点与业务主体配置一次动作，返回可供测试协调线程的栅栏。
     * <p>
     * subject=* 可匹配该点的任意主体；精确配置优先。同键重复配置直接替换尚未消费的栅栏，
     * runId 不参与匹配，因此不同运行应避免无协调地复用相同键。
     *
     * @param point
     *                    业务代码的故障点
     * @param subject
     *                    请求 ID、活动 ID 等命中主体，或 *
     * @param runId
     *                    到达证据中的运行标识
     * @param action
     *                    FAIL 抛异常，PAUSE 等待许可
     *
     * @return 新栅栏，测试可等待 reached 或释放 release
     * @throws IllegalStateException
     *                                   故障注入未启用
     */
    public Barrier arm(FaultPoint point, String subject, String runId, Action action) {
        if (!config.enabled())
            throw new IllegalStateException("故障注入未启用");
        var barrier = new Barrier(runId, action);
        armed.put(new Key(point, subject), barrier);
        return barrier;
    }

    /**
     * 释放当前可见的待命及活动栅栏，再清空待命配置，便于测试清理。
     * <p>
     * 不会删除证据文件；并发 hit/arm 仍可能交错，因此这不是严格的全局停止屏障。
     */
    public void clear() {
        armed.values().forEach(b -> b.release.countDown());
        active.forEach(b -> b.release.countDown());
        armed.clear();
    }

    /**
     * 尝试消费一次匹配配置，写到达证据，再执行 FAIL 或 PAUSE；未启用或未匹配时直接返回。
     * <p>
     * 先移除精确配置，否则移除通配配置。JSONL 包含 runId、subject、eventId、point、时间和 PID，
     * 同一注入器实例内串行追加；这不提供跨进程文件事务。写入成功才通知 reached。
     * <p>
     * FAIL 抛受控异常；PAUSE 最多等待 waitMillis，中断时恢复线程中断标记并抛异常。
     * 配置在写证据之前已被消费，I/O 失败也不会自动重新布置。
     *
     * @param point
     *                    当前代码执行到的故障点
     * @param subject
     *                    当前业务主体，恢复流程使用活动 ID 的字符串
     * @param eventId
     *                    证据关联标识，可为事件 ID 或恢复 ID；为空时记为空串，不参与匹配
     * @throws InjectedFault
     *                                   命中 FAIL，或 PAUSE 等待超时、中断
     * @throws IllegalStateException
     *                                   到达证据写入失败
     */
    public void hit(FaultPoint point, String subject, String eventId) {
        if (!config.enabled())
            return;
        Barrier barrier = armed.remove(new Key(point, subject));
        if (barrier == null)
            barrier = armed.remove(new Key(point, "*"));
        if (barrier == null)
            return;
        active.add(barrier);
        try {
            var path = Path.of(config.evidencePath()).toAbsolutePath();
            Files.createDirectories(path.getParent());
            String line = mapper
                    .writeValueAsString(Map.of("runId", barrier.runId, "subject", subject, "eventId",
                            eventId == null ? "" : eventId,
                            "point", point.name(), "atMs", clock.millis(), "pid", ProcessHandle.current().pid()))
                    + System.lineSeparator();
            synchronized (this) {
                Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            barrier.reached.countDown();
            if (barrier.action == Action.FAIL)
                throw new InjectedFault(point.name());
            if (!barrier.release.await(config.waitMillis(), TimeUnit.MILLISECONDS))
                throw new InjectedFault("栅栏等待超时: " + point);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InjectedFault("栅栏中断: " + point);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("故障到达证据写入失败", ex);
        } finally {
            active.remove(barrier);
        }
    }
}

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

/** 默认关闭，无 HTTP 控制接口；测试用精确栅栏控制交错，证据只证明代码到达此点。 */
@Component
public class FaultInjector {
    public enum Action {
        FAIL, PAUSE
    }

    public static class InjectedFault extends RuntimeException {
        public InjectedFault(String point) {
            super(point);
        }
    }

    public static class Barrier {
        public final CountDownLatch reached = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
        final String runId;
        final Action action;

        Barrier(String runId, Action action) {
            this.runId = runId;
            this.action = action;
        }
    }

    private record Key(FaultPoint point, String subject) {
    }

    private final ConcurrentHashMap<Key, Barrier> armed = new ConcurrentHashMap<>();
    private final java.util.Set<Barrier> active = ConcurrentHashMap.newKeySet();
    private final FaultProperties config;
    private final Clock clock;
    private final ObjectMapper mapper;

    public FaultInjector(FaultProperties config, Clock clock, ObjectMapper mapper) {
        this.config = config;
        this.clock = clock;
        this.mapper = mapper;
        if (config.enabled() && !config.point().isBlank())
            arm(FaultPoint.valueOf(config.point()), config.subject(), config.runId(), Action.valueOf(config.action()));
    }

    public Barrier arm(FaultPoint point, String subject, String runId, Action action) {
        if (!config.enabled())
            throw new IllegalStateException("故障注入未启用");
        var barrier = new Barrier(runId, action);
        armed.put(new Key(point, subject), barrier);
        return barrier;
    }

    public void clear() {
        armed.values().forEach(b -> b.release.countDown());
        active.forEach(b -> b.release.countDown());
        armed.clear();
    }

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

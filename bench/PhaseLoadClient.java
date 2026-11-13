import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方向 02 分阶段开环压测客户端。
 *
 * 用法：
 * java bench/PhaseLoadClient.java --url http://localhost:8080 --voucher 9201 \
 * --tokens tmp/02/tokens.txt --base-rate 200 --phases 0.7:60,1.5:120,0.7:120 \
 * --warmup 30 --concurrency 128 --max-inflight 512 --output tmp/02/run-...
 *
 * phases 的每一项是“倍数:秒”，倍数为相对 base-rate 的到达率；0 表示暂停。
 * 每个阶段单独记录目标/实际受理/拒绝/HTTP 延迟；阶段结束后等待在途请求完成。
 */
public final class PhaseLoadClient {

    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");

    private record Phase(String name, double factor, double rate, int seconds,
            long startEpochMillis, long endEpochMillis) {
    }

    private static final class PhaseCounters {
        final AtomicLong attempts = new AtomicLong();
        final AtomicLong accepted = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong unknown = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final ConcurrentLinkedQueue<Long> acceptedLatencies = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> allLatencies = new ConcurrentLinkedQueue<>();
        final Map<Integer, AtomicLong> statuses = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> acceptedRows = new ConcurrentLinkedQueue<>();

        void status(int code) {
            statuses.computeIfAbsent(code, ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String baseUrl = options.getOrDefault("url", "http://localhost:8080");
        long voucherId = Long.parseLong(required(options, "voucher"));
        Path tokenFile = Path.of(required(options, "tokens"));
        double baseRate = Double.parseDouble(required(options, "base-rate"));
        int warmupSeconds = Integer.parseInt(options.getOrDefault("warmup", "0"));
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", "128"));
        int maxInflight = Integer.parseInt(options.getOrDefault("max-inflight", "512"));
        int tokenOffset = Integer.parseInt(options.getOrDefault("token-offset", "0"));
        Path output = Path.of(required(options, "output"));
        List<PhaseSpec> specs = parsePhases(required(options, "phases"));
        if (baseRate <= 0 || specs.isEmpty()) {
            throw new IllegalArgumentException("base-rate 和 phases 必须有效");
        }
        Files.createDirectories(output);

        List<String> tokens = Files.readAllLines(tokenFile, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (tokenOffset < 0 || tokenOffset >= tokens.size()) {
            throw new IllegalArgumentException("token-offset 超出 token 文件范围");
        }

        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(3)).build();
        URI uri = URI.create(baseUrl + "/api/seckill/vouchers/" + voucherId);
        AtomicInteger tokenIndex = new AtomicInteger(tokenOffset);
        AtomicInteger active = new AtomicInteger();
        Semaphore inFlight = new Semaphore(maxInflight);

        long startEpochMillis = System.currentTimeMillis();
        long startNanos = System.nanoTime();
        List<Phase> phases = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            PhaseSpec spec = specs.get(i);
            Phase phase = new Phase("phase-" + (i + 1), spec.factor(),
                    baseRate * spec.factor(), spec.seconds(), 0, 0);
            phases.add(phase);
        }
        PhaseCounters warmup = new PhaseCounters();
        long warmupEnd = startNanos + warmupSeconds * 1_000_000_000L;
        if (warmupSeconds > 0) {
            dispatch(warmupEnd, phases.get(0).rate(), startNanos, client, uri, tokens,
                    tokenIndex, inFlight, active, warmup);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (active.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }

        List<PhaseCounters> counters = new ArrayList<>();
        List<Phase> measured = new ArrayList<>();
        long cursor = System.nanoTime();
        for (Phase phase : phases) {
            long phaseStart = System.nanoTime();
            long phaseEnd = phaseStart + phase.seconds() * 1_000_000_000L;
            PhaseCounters counter = new PhaseCounters();
            dispatch(phaseEnd, phase.rate(), phaseStart, client, uri, tokens,
                    tokenIndex, inFlight, active, counter);
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (active.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            long phaseEndActual = System.nanoTime();
            measured.add(new Phase(phase.name(), phase.factor(), phase.rate(),
                    phase.seconds(), startEpochMillis + (phaseStart - startNanos) / 1_000_000,
                    startEpochMillis + (phaseEndActual - startNanos) / 1_000_000));
            counters.add(counter);
            cursor = phaseEndActual;
        }

        long drainStart = System.nanoTime();
        long idleDeadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (active.get() > 0 && System.nanoTime() < idleDeadline) {
            Thread.sleep(50);
        }
        long drainSeconds = Math.max(0, (System.nanoTime() - drainStart) / 1_000_000_000L);

        writeOutputs(output, baseRate, voucherId, warmupSeconds, concurrency,
                maxInflight, tokens.size(), tokenOffset, startEpochMillis,
                phases, measured, counters, drainSeconds);
        printSummary(output, baseRate, measured, counters, drainSeconds);
    }

    private static void dispatch(long endNanos, double rate, long phaseStartNanos,
            HttpClient client, URI uri, List<String> tokens,
            AtomicInteger tokenIndex, Semaphore inFlight,
            AtomicInteger active, PhaseCounters counters) {
        if (rate <= 0) {
            while (System.nanoTime() < endNanos) {
                LockSupport.parkNanos(50_000_000L);
            }
            return;
        }
        long intervalNanos = (long) (1_000_000_000d / rate);
        long sequence = 0;
        while (true) {
            long target = phaseStartNanos + sequence * intervalNanos;
            if (target >= endNanos) {
                break;
            }
            long sleep = target - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            }
            sequence++;
            if (!inFlight.tryAcquire()) {
                counters.skipped.incrementAndGet();
                continue;
            }
            int slot = tokenIndex.getAndIncrement();
            if (slot >= tokens.size()) {
                counters.skipped.incrementAndGet();
                inFlight.release();
                continue;
            }
            String token = tokens.get(slot);
            active.incrementAndGet();
            Thread.startVirtualThread(() -> {
                long requestStart = System.nanoTime();
                long requestEpoch = System.currentTimeMillis();
                try {
                    HttpRequest request = HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.noBody()).build();
                    HttpResponse<String> response = client.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    long elapsed = System.nanoTime() - requestStart;
                    counters.attempts.incrementAndGet();
                    counters.status(response.statusCode());
                    counters.allLatencies.add(elapsed);
                    String body = response.body();
                    boolean accepted = response.statusCode() == 202 && body.contains("\"code\":0");
                    if (accepted) {
                        counters.accepted.incrementAndGet();
                        counters.acceptedLatencies.add(elapsed);
                        if (body.contains("\"status\":\"UNKNOWN\"")) {
                            counters.unknown.incrementAndGet();
                        }
                        Matcher matcher = REQUEST_ID.matcher(body);
                        if (matcher.find()) {
                            counters.acceptedRows.add(matcher.group(1) + "," + requestEpoch);
                        }
                    } else {
                        counters.rejected.incrementAndGet();
                    }
                } catch (Exception ex) {
                    counters.errors.incrementAndGet();
                    counters.attempts.incrementAndGet();
                } finally {
                    active.decrementAndGet();
                    inFlight.release();
                }
            });
        }
        // 当前阶段调度结束，未发出的请求已经由 endNanos 截止；在途请求由调用方等待。
    }

    private static void writeOutputs(Path output, double baseRate, long voucherId,
            int warmupSeconds, int concurrency, int maxInflight,
            int tokenCount, int tokenOffset, long startEpochMillis,
            List<Phase> phases, List<Phase> measured,
            List<PhaseCounters> counters, long drainSeconds) throws IOException {
        try (BufferedWriter summary = Files.newBufferedWriter(output.resolve("phase-summary.csv"),
                StandardCharsets.UTF_8);
                BufferedWriter accepted = Files.newBufferedWriter(output.resolve("accepted_requests.csv"),
                        StandardCharsets.UTF_8)) {
            summary.write("phase,factor,target_rate,configured_seconds,start_epoch_ms,end_epoch_ms,"
                    + "attempts,accepted,rejected,unknown,errors,skipped,"
                    + "http_p50_all_ms,http_p95_all_ms,http_p99_all_ms,"
                    + "http_p99_accepted_ms,statuses\n");
            accepted.write("requestId,start_epoch_ms,phase\n");
            for (int i = 0; i < phases.size(); i++) {
                Phase p = measured.get(i);
                PhaseCounters c = counters.get(i);
                List<Long> all = new ArrayList<>(c.allLatencies);
                List<Long> ok = new ArrayList<>(c.acceptedLatencies);
                summary.write(String.format("%s,%.3f,%.3f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,\"%s\"%n",
                        p.name(), p.factor(), p.rate(), p.seconds(), p.startEpochMillis(),
                        System.currentTimeMillis(), c.attempts.get(), c.accepted.get(),
                        c.rejected.get(), c.unknown.get(), c.errors.get(), c.skipped.get(),
                        percentileMillis(all, 50), percentileMillis(all, 95),
                        percentileMillis(all, 99), percentileMillis(ok, 99), c.statuses));
                for (String row : c.acceptedRows) {
                    accepted.write(row + "," + p.name() + System.lineSeparator());
                }
            }
        }
        Files.writeString(output.resolve("run-meta.txt"), """
                voucher_id=%d
                base_rate=%.3f
                warmup_seconds=%d
                concurrency=%d
                max_inflight=%d
                token_count=%d
                token_offset=%d
                start_epoch_ms=%d
                drain_seconds=%d
                phases=%s
                """.formatted(voucherId, baseRate, warmupSeconds, concurrency, maxInflight,
                tokenCount, tokenOffset, startEpochMillis, drainSeconds,
                phases.stream().map(p -> p.factor() + ":" + p.seconds()).toList()),
                StandardCharsets.UTF_8);
    }

    private static void printSummary(Path output, double baseRate, List<Phase> phases,
            List<PhaseCounters> counters, long drainSeconds) {
        System.out.println("output=" + output);
        System.out.println("base_rate=" + baseRate + " drain_seconds=" + drainSeconds);
        for (int i = 0; i < phases.size(); i++) {
            PhaseCounters c = counters.get(i);
            System.out.printf("%s factor=%.2f target=%.2f accepted=%d rejected=%d errors=%d skipped=%d%n",
                    phases.get(i).name(), phases.get(i).factor(), phases.get(i).rate(),
                    c.accepted.get(), c.rejected.get(), c.errors.get(), c.skipped.get());
        }
    }

    private static double percentileMillis(List<Long> nanos, double percentile) {
        if (nanos.isEmpty()) {
            return Double.NaN;
        }
        Collections.sort(nanos);
        int index = (int) Math.ceil(percentile / 100.0 * nanos.size()) - 1;
        return nanos.get(Math.max(0, Math.min(nanos.size() - 1, index))) / 1_000_000.0;
    }

    private static List<PhaseSpec> parsePhases(String value) {
        List<PhaseSpec> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String[] pieces = part.trim().split(":");
            if (pieces.length != 2) {
                throw new IllegalArgumentException("phase 格式应为 factor:seconds");
            }
            double factor = Double.parseDouble(pieces[0]);
            int seconds = Integer.parseInt(pieces[1]);
            if (factor < 0 || seconds <= 0) {
                throw new IllegalArgumentException("phase factor 必须非负且 seconds 必须为正");
            }
            result.add(new PhaseSpec(factor, seconds));
        }
        return result;
    }

    private record PhaseSpec(double factor, int seconds) {
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new ConcurrentHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("未知参数: " + args[i]);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("参数缺少值: " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数 --" + key);
        }
        return value;
    }
}

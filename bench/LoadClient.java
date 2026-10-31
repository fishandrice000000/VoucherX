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
 * VoucherX 秒杀压测客户端（JDK 21 单文件，无第三方依赖）。
 *
 * 用法：
 * java bench/LoadClient.java --url http://localhost:8080 --voucher 9001 \
 *   --tokens tmp/01/tokens.txt --rate 500 --warmup 30 --duration 120 \
 *   --concurrency 256 --max-inflight 512 --sample-status 100 \
 *   --output tmp/01/run-...
 *
 * rate > 0 为开环限速；rate = 0 为固定并发闭环。开环模式在 in-flight 达到上限时
 * 记录 skipped，不把未发出的请求算作系统吞吐。
 */
public final class LoadClient {

    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\":\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z_]+)\"");

    private record Sample(String requestId, String token, long startNanos) {
    }

    private static final class Counters {
        final AtomicLong accepted = new AtomicLong();
        final AtomicLong unknown = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong skipped = new AtomicLong();
        final AtomicLong attempts = new AtomicLong();
        final AtomicLong measuredAttempts = new AtomicLong();
        final AtomicLong measuredAccepted = new AtomicLong();
        final AtomicLong measuredUnknown = new AtomicLong();
        final AtomicLong measuredRejected = new AtomicLong();
        final AtomicLong maxDispatchLagNanos = new AtomicLong();
        final Map<Integer, AtomicLong> httpStatuses = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> e2eNanos = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> e2eRows = new ConcurrentLinkedQueue<>();
        final AtomicLong e2eFailed = new AtomicLong();
        final AtomicLong e2eSamples = new AtomicLong();

        void httpStatus(int status) {
            httpStatuses.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseOptions(args);
        String baseUrl = options.getOrDefault("url", "http://localhost:8080");
        long voucherId = Long.parseLong(required(options, "voucher"));
        Path tokenFile = Path.of(required(options, "tokens"));
        double rate = Double.parseDouble(options.getOrDefault("rate", "0"));
        int warmupSeconds = Integer.parseInt(options.getOrDefault("warmup", "30"));
        int durationSeconds = Integer.parseInt(options.getOrDefault("duration", "120"));
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", "256"));
        int maxInflight = Integer.parseInt(options.getOrDefault("max-inflight", "512"));
        int sampleStatus = Integer.parseInt(options.getOrDefault("sample-status", "30"));
        long sampleIntervalMillis = Long.parseLong(options.getOrDefault("sample-interval-ms", "1000"));
        Path output = Path.of(required(options, "output"));
        Files.createDirectories(output);

        List<String> tokens = Files.readAllLines(tokenFile, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (tokens.size() < 2) {
            throw new IllegalArgumentException("token 文件内容不足");
        }

        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        URI seckillUri = URI.create(baseUrl + "/api/seckill/vouchers/" + voucherId);

        Counters counters = new Counters();
        AtomicInteger tokenIndex = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        Semaphore inFlight = new Semaphore(maxInflight);

        long startEpochMillis = System.currentTimeMillis();
        long warmupNanos = warmupSeconds * 1_000_000_000L;
        long measureNanos = durationSeconds * 1_000_000_000L;
        long startNanos = System.nanoTime();
        long measureStart = startNanos + warmupNanos;
        long endNanos = measureStart + measureNanos;

        long expectedMeasureRequests = rate > 0
                ? Math.round(rate * durationSeconds)
                : Math.max(1, durationSeconds * concurrency);
        long sampleEvery = Math.max(1, expectedMeasureRequests / Math.max(1, sampleStatus));

        Runnable requestTask = () -> {
            int slot = tokenIndex.getAndIncrement();
            if (slot >= tokens.size()) {
                counters.skipped.incrementAndGet();
                inFlight.release();
                return;
            }
            String token = tokens.get(slot);
            long requestStart = System.nanoTime();
            try {
                HttpRequest request = HttpRequest.newBuilder(seckillUri)
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long elapsed = System.nanoTime() - requestStart;
                counters.attempts.incrementAndGet();
                counters.httpStatus(response.statusCode());

                String body = response.body();
                boolean accepted = response.statusCode() == 202 && body.contains("\"code\":0");
                boolean unknown = accepted && body.contains("\"status\":\"UNKNOWN\"");
                if (accepted) {
                    counters.accepted.incrementAndGet();
                    if (unknown) {
                        counters.unknown.incrementAndGet();
                    }
                } else {
                    counters.rejected.incrementAndGet();
                }

                if (requestStart >= measureStart) {
                    counters.measuredAttempts.incrementAndGet();
                    counters.latenciesNanos.add(elapsed);
                    if (accepted) {
                        counters.measuredAccepted.incrementAndGet();
                        if (unknown) {
                            counters.measuredUnknown.incrementAndGet();
                        }
                    } else {
                        counters.measuredRejected.incrementAndGet();
                    }

                    Matcher idMatcher = REQUEST_ID.matcher(body);
                    if (accepted && idMatcher.find()
                            && counters.measuredAccepted.get() % sampleEvery == 0
                            && counters.samples.size() < sampleStatus) {
                        String requestId = idMatcher.group(1);
                        Sample sample = new Sample(requestId, token, requestStart);
                        counters.samples.add(sample);
                        Thread.startVirtualThread(() ->
                                pollStatus(client, baseUrl, sample, counters, sampleIntervalMillis));
                    }
                }
            } catch (Exception ex) {
                counters.errors.incrementAndGet();
                if (requestStart >= measureStart) {
                    counters.measuredAttempts.incrementAndGet();
                }
            } finally {
                active.decrementAndGet();
                inFlight.release();
            }
        };

        if (rate > 0) {
            long totalRequests = Math.round(rate * (warmupSeconds + durationSeconds));
            long intervalNanos = (long) (1_000_000_000d / rate);
            long previousTarget = startNanos;
            for (long i = 0; i < totalRequests; i++) {
                long target = startNanos + i * intervalNanos;
                long sleep = target - System.nanoTime();
                if (sleep > 0) {
                    LockSupport.parkNanos(sleep);
                }
                long lag = System.nanoTime() - target;
                counters.maxDispatchLagNanos.accumulateAndGet(Math.max(0, lag), Math::max);

                if (tokenIndex.get() >= tokens.size()) {
                    counters.skipped.incrementAndGet();
                    continue;
                }
                if (!inFlight.tryAcquire()) {
                    counters.skipped.incrementAndGet();
                    continue;
                }
                active.incrementAndGet();
                Thread.startVirtualThread(requestTask);
                previousTarget = target;
            }
            if (previousTarget > endNanos) {
                System.err.println("警告: 开环调度已越过测量结束时间，存在调度滞后");
            }
        } else {
            List<Thread> workers = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                Thread worker = Thread.startVirtualThread(() -> {
                    while (System.nanoTime() < endNanos && tokenIndex.get() < tokens.size()) {
                        if (!inFlight.tryAcquire()) {
                            LockSupport.parkNanos(1_000_000L);
                            continue;
                        }
                        active.incrementAndGet();
                        requestTask.run();
                    }
                });
                workers.add(worker);
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }

        long waitDeadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (active.get() > 0 && System.nanoTime() < waitDeadline) {
            Thread.sleep(50);
        }
        // 给采样状态查询留出收敛时间，但不阻塞过久。
        long sampleDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while ((counters.samples.size() > counters.e2eSamples.get() + counters.e2eFailed.get())
                && System.nanoTime() < sampleDeadline) {
            Thread.sleep(100);
        }

        writeOutputs(output, rate, voucherId, warmupSeconds, durationSeconds, concurrency,
                maxInflight, tokens.size(), startEpochMillis,
                startEpochMillis + warmupSeconds * 1000L,
                startEpochMillis + (warmupSeconds + durationSeconds) * 1000L,
                counters);
        printSummary(output, rate, counters);
    }

    private static void pollStatus(HttpClient client, String baseUrl, Sample sample, Counters counters,
                                   long sampleIntervalMillis) {
        URI uri = URI.create(baseUrl + "/api/seckill/requests/" + sample.requestId());
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        try {
            while (System.nanoTime() < deadline) {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + sample.token())
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                Matcher matcher = STATUS.matcher(response.body());
                if (response.statusCode() == 200 && matcher.find()) {
                    String status = matcher.group(1);
                    if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                        long e2e = System.nanoTime() - sample.startNanos();
                        counters.e2eNanos.add(e2e);
                        counters.e2eSamples.incrementAndGet();
                        counters.e2eRows.add(sample.requestId() + "," + status + "," + (e2e / 1_000_000L));
                        return;
                    }
                }
                Thread.sleep(sampleIntervalMillis);
            }
            counters.e2eFailed.incrementAndGet();
            counters.e2eRows.add(sample.requestId() + ",TIMEOUT,");
        } catch (Exception ex) {
            counters.e2eFailed.incrementAndGet();
            counters.e2eRows.add(sample.requestId() + ",ERROR,");
        }
    }

    private static void writeOutputs(Path output, double rate, long voucherId,
                                     int warmup, int duration, int concurrency,
                                     int maxInflight, int tokens,
                                     long startEpochMillis, long measureStartEpochMillis,
                                     long measureEndEpochMillis, Counters counters) throws IOException {
        List<Long> latencies = new ArrayList<>(counters.latenciesNanos);
        List<Long> e2e = new ArrayList<>(counters.e2eNanos);
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("summary.txt"))) {
            writer.write("start_epoch_ms=" + startEpochMillis + "\n");
            writer.write("measure_start_epoch_ms=" + measureStartEpochMillis + "\n");
            writer.write("measure_end_epoch_ms=" + measureEndEpochMillis + "\n");
            writer.write("end_epoch_ms=" + System.currentTimeMillis() + "\n");
            writer.write("voucher_id=" + voucherId + "\n");
            writer.write("target_rate_per_sec=" + rate + "\n");
            writer.write("warmup_seconds=" + warmup + "\n");
            writer.write("measure_seconds=" + duration + "\n");
            writer.write("concurrency=" + concurrency + "\n");
            writer.write("max_inflight=" + maxInflight + "\n");
            writer.write("tokens_available=" + tokens + "\n");
            writer.write("total_attempts=" + counters.attempts.get() + "\n");
            writer.write("accepted_total=" + counters.accepted.get() + "\n");
            writer.write("unknown_total=" + counters.unknown.get() + "\n");
            writer.write("rejected_total=" + counters.rejected.get() + "\n");
            writer.write("skipped_total=" + counters.skipped.get() + "\n");
            writer.write("errors_total=" + counters.errors.get() + "\n");
            writer.write("measured_attempts=" + counters.measuredAttempts.get() + "\n");
            writer.write("measured_accepted=" + counters.measuredAccepted.get() + "\n");
            writer.write("measured_unknown=" + counters.measuredUnknown.get() + "\n");
            writer.write("measured_rejected=" + counters.measuredRejected.get() + "\n");
            writer.write("measured_attempt_rate="
                    + (counters.measuredAttempts.get() / (double) duration) + "\n");
            writer.write("measured_accept_rate="
                    + (counters.measuredAccepted.get() / (double) duration) + "\n");
            writer.write("http_p50_ms=" + percentileMs(latencies, 50) + "\n");
            writer.write("http_p95_ms=" + percentileMs(latencies, 95) + "\n");
            writer.write("http_p99_ms=" + percentileMs(latencies, 99) + "\n");
            writer.write("max_dispatch_lag_ms=" + counters.maxDispatchLagNanos.get() / 1_000_000L + "\n");
            writer.write("e2e_samples_done=" + counters.e2eSamples.get() + "\n");
            writer.write("e2e_failed=" + counters.e2eFailed.get() + "\n");
            writer.write("e2e_p50_ms=" + percentileMs(e2e, 50) + "\n");
            writer.write("e2e_p95_ms=" + percentileMs(e2e, 95) + "\n");
            writer.write("e2e_p99_ms=" + percentileMs(e2e, 99) + "\n");
            for (Map.Entry<Integer, AtomicLong> entry : counters.httpStatuses.entrySet()) {
                writer.write("http_status_" + entry.getKey() + "=" + entry.getValue().get() + "\n");
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("e2e.csv"))) {
            writer.write("request_id,status,e2e_ms\n");
            for (String row : counters.e2eRows) {
                writer.write(row);
                writer.write('\n');
            }
        }
        long[] latencyArray = latencies.stream().mapToLong(Long::longValue).toArray();
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("latencies_ms.csv"))) {
            writer.write("latency_ms\n");
            for (long value : latencyArray) {
                writer.write(Long.toString(value / 1_000_000L));
                writer.write('\n');
            }
        }
    }

    private static long percentileMs(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000L;
    }

    private static void printSummary(Path output, double rate, Counters counters) {
        System.out.println("========== Load summary ==========");
        System.out.println("output=" + output);
        System.out.println("target_rate=" + rate);
        System.out.println("measured_attempts=" + counters.measuredAttempts.get());
        System.out.println("measured_accepted=" + counters.measuredAccepted.get());
        System.out.println("measured_rejected=" + counters.measuredRejected.get());
        System.out.println("measured_unknown=" + counters.measuredUnknown.get());
        System.out.println("skipped=" + counters.skipped.get());
        System.out.println("errors=" + counters.errors.get());
        System.out.println("e2e_samples=" + counters.e2eSamples.get()
                + ", failed=" + counters.e2eFailed.get());
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new ConcurrentHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("无法识别参数: " + args[i]);
            }
            String key = args[i].substring(2);
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("参数缺少值: " + key);
            }
            options.put(key, args[++i]);
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

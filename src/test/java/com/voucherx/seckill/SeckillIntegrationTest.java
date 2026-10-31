package com.voucherx.seckill;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.mq.publisher.SeckillMessagePublisher;
import com.voucherx.order.entity.VoucherOrder;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.order.service.OrderBusinessException;
import com.voucherx.order.service.OrderFailure;
import com.voucherx.order.service.OrderPersistenceService;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.dto.SeckillRequestStatusResponse;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.model.SeckillRequestStatus;
import com.voucherx.seckill.service.SeckillRequestStatusStore;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockInitializer;
import com.voucherx.seckill.service.SeckillStockService;
import com.voucherx.voucher.mapper.SeckillVoucherBucketMapper;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class SeckillIntegrationTest extends AbstractIntegrationTest {

    private static final long VOUCHER_ID = 2001L;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillStockService stockService;
    @Autowired
    private SeckillRequestStatusStore statusStore;
    @Autowired
    private SeckillMessagePublisher publisher;
    @Autowired
    private VoucherOrderMapper orderMapper;
    @Autowired
    private SeckillVoucherMapper seckillVoucherMapper;
    @Autowired
    private SeckillVoucherBucketMapper bucketMapper;
    @Autowired
    private SeckillStockInitializer stockInitializer;
    @Autowired
    private OrderPersistenceService orderPersistenceService;

    @Test
    void activityNotStartedIsRejectedBeforeStockReservation() {
        createUser(1);
        LocalDateTime now = LocalDateTime.now();
        createActivity(VOUCHER_ID, 10, 10, now.plusHours(1), now.plusHours(2));
        stockService.initializeIfAbsent(VOUCHER_ID, 10);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_NOT_STARTED));
        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(10L);
    }

    @Test
    void activityEndedIsRejected() {
        createUser(1);
        LocalDateTime now = LocalDateTime.now();
        createActivity(VOUCHER_ID, 10, 10, now.minusHours(2), now.minusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 10);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_ENDED));
    }

    @Test
    void soldOutAndMissingStockStateAreDistinguished() {
        createUser(1);
        LocalDateTime now = LocalDateTime.now();
        createActivity(VOUCHER_ID, 10, 0, now.minusHours(1), now.plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 0);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_SOLD_OUT));

        redisTemplate.delete("voucherx:seckill:stock:" + VOUCHER_ID);
        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_STOCK_UNAVAILABLE));
    }

    @Test
    void oneUserCanOnlyReserveOnce() {
        createUser(1);
        createActivity(VOUCHER_ID, 2, 2, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 2);

        SeckillAcceptResponse first = seckillService.seckill(1L, VOUCHER_ID);
        assertThat(first.status()).isEqualTo(SeckillRequestStatus.PROCESSING);
        awaitOrderCount(1);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_DUPLICATE));

        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(1L);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1);
    }

    @Test
    void asynchronousResultPrefersCommittedMysqlOrder() {
        createUser(1);
        createUser(2);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 1);

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            SeckillRequestStatusResponse status =
                    seckillService.queryRequest(1L, accepted.requestId());
            assertThat(status.status()).isEqualTo(SeckillRequestStatus.SUCCESS);
            assertThat(status.orderId()).isNotNull();
            assertThat(status.messageId()).isNotBlank();
        });

        SeckillRequestStatusResponse status = seckillService.queryRequest(1L, accepted.requestId());
        assertThat(orderMapper.selectById(status.orderId()).getRequestId())
                .isEqualTo(accepted.requestId());
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();

        assertThatThrownBy(() -> seckillService.queryRequest(2L, accepted.requestId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_NOT_FOUND));
    }

    @Test
    void duplicateMessageDoesNotCreateSecondOrderOrDeductStockAgain() throws Exception {
        createUser(1);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 1);

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID);
        awaitOrderCount(1);
        String requestId = accepted.requestId();
        var existing = orderMapper.selectByRequestId(requestId);

        SeckillOrderMessage duplicate = new SeckillOrderMessage(
                existing.getMessageId(), requestId, 1L, VOUCHER_ID,
                System.currentTimeMillis(), "SINGLE", 0, 1);
        assertThat(publisher.publish(duplicate).name()).isEqualTo("CONFIRMED");
        assertThat(publisher.publish(duplicate).name()).isEqualTo("CONFIRMED");

        Thread.sleep(1200);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1);
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isZero();
        assertThat(statusStore.find(requestId).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void unroutableMessageIsTreatedAsDefiniteFailureAndReleasesReservation() {
        createUser(1);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 1);

        Binding binding = new Binding(RabbitMqConfig.MAIN_QUEUE, Binding.DestinationType.QUEUE,
                RabbitMqConfig.MAIN_EXCHANGE, RabbitMqConfig.MAIN_ROUTING_KEY, null);
        rabbitAdmin.removeBinding(binding);
        try {
            assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.errorCode())
                                    .isEqualTo(ErrorCode.SECKILL_PUBLISH_FAILED));
        } finally {
            rabbitAdmin.declareBinding(binding);
        }

        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(1L);
        assertThat(redisTemplate.opsForSet().isMember(
                RedisKeys.seckillUsers(VOUCHER_ID), "1")).isFalse();
    }

    @Test
    void releaseIsIdempotent() {
        createActivity(VOUCHER_ID, 5, 5, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 5);

        String requestId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long begin = now - 3_600_000L;
        long end = now + 3_600_000L;
        assertThat(stockService.reserve(VOUCHER_ID, 1L, requestId, now, begin, end, "SINGLE", 0, 1)).isEqualTo(4L);

        assertThat(stockService.release(VOUCHER_ID, 1L, requestId, "TEST")).isTrue();
        assertThat(stockService.release(VOUCHER_ID, 1L, requestId, "TEST")).isFalse();
        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(5L);
    }

    @Test
    void databaseUniqueConstraintRejectsSecondOrderForSameUserAndVoucher() {
        createActivity(VOUCHER_ID, 2, 2, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));

        VoucherOrder first = new VoucherOrder();
        first.setRequestId(UUID.randomUUID().toString());
        first.setMessageId(UUID.randomUUID().toString());
        first.setUserId(1L);
        first.setVoucherId(VOUCHER_ID);
        first.setStatus(VoucherOrder.STATUS_CREATED);
        orderMapper.insert(first);

        VoucherOrder duplicate = new VoucherOrder();
        duplicate.setRequestId(UUID.randomUUID().toString());
        duplicate.setMessageId(UUID.randomUUID().toString());
        duplicate.setUserId(1L);
        duplicate.setVoucherId(VOUCHER_ID);
        duplicate.setStatus(VoucherOrder.STATUS_CREATED);

        assertThatThrownBy(() -> orderMapper.insert(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1);
    }

    @Test
    void lateEntryStatusCannotOverwriteTerminalState() {
        createActivity(VOUCHER_ID, 10, 10, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 10);
        long now = System.currentTimeMillis();
        long begin = now - 3_600_000L;
        long end = now + 3_600_000L;

        String successRequest = UUID.randomUUID().toString();
        stockService.reserve(VOUCHER_ID, 1L, successRequest, now, begin, end, "SINGLE", 0, 1);
        statusStore.markSuccess(successRequest, "message-success", 123L);
        statusStore.markProcessing(successRequest, "message-late");
        statusStore.markUnknown(successRequest, "message-late", "LATE");
        statusStore.markFailed(successRequest, "message-late", "LATE");
        assertThat(statusStore.find(successRequest))
                .containsEntry("status", "SUCCESS")
                .containsEntry("orderId", "123");

        String failedRequest = UUID.randomUUID().toString();
        stockService.reserve(VOUCHER_ID, 2L, failedRequest, now, begin, end, "SINGLE", 0, 1);
        statusStore.markFailed(failedRequest, "message-failed", "SOLD_OUT");
        statusStore.markProcessing(failedRequest, "message-late");
        statusStore.markUnknown(failedRequest, "message-late", "LATE");
        assertThat(statusStore.find(failedRequest)).containsEntry("status", "FAILED");

        String unknownRequest = UUID.randomUUID().toString();
        stockService.reserve(VOUCHER_ID, 3L, unknownRequest, now, begin, end, "SINGLE", 0, 1);
        statusStore.markUnknown(unknownRequest, "message-unknown", "PUBLISH_CONFIRM_UNKNOWN");
        statusStore.markProcessing(unknownRequest, "message-late");
        assertThat(statusStore.find(unknownRequest)).containsEntry("status", "UNKNOWN");
        statusStore.markSuccess(unknownRequest, "message-success", 456L);
        assertThat(statusStore.find(unknownRequest))
                .containsEntry("status", "SUCCESS")
                .containsEntry("orderId", "456");

        String releasedRequest = UUID.randomUUID().toString();
        stockService.reserve(VOUCHER_ID, 4L, releasedRequest, now, begin, end, "SINGLE", 0, 1);
        assertThat(stockService.release(VOUCHER_ID, 4L, releasedRequest, "TEST")).isTrue();
        statusStore.markProcessing(releasedRequest, "message-late");
        assertThat(statusStore.find(releasedRequest)).containsEntry("status", "RELEASED");
    }

    @Test
    void orderInsertFailureRollsBackStockDeduction() {
        createActivity(VOUCHER_ID, 3, 3, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 3);

        SeckillOrderMessage invalid = new SeckillOrderMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                null, VOUCHER_ID, System.currentTimeMillis(), "SINGLE", 0, 1);

        assertThatThrownBy(() -> orderPersistenceService.persist(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isEqualTo(3);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
    }

    @Test
    void bucketAllocationHandlesRemainderAndZeroBuckets() {
        createBucketActivity(VOUCHER_ID, 5, 8, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        assertThat(bucketMapper.countBuckets(VOUCHER_ID, 1)).isEqualTo(8);
        assertThat(bucketMapper.sumTotal(VOUCHER_ID, 1)).isEqualTo(5);
        Integer bucketsWithOne = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tb_seckill_voucher_bucket
                 WHERE voucher_id = ? AND total_stock = 1
                """, Integer.class, VOUCHER_ID);
        Integer emptyBuckets = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tb_seckill_voucher_bucket
                 WHERE voucher_id = ? AND total_stock = 0
                """, Integer.class, VOUCHER_ID);
        assertThat(bucketsWithOne).isEqualTo(5);
        assertThat(emptyBuckets).isEqualTo(3);

        stockService.initializeIfAbsent(VOUCHER_ID, 5);
        // 用户 1..4 与 8 的起始桶分别为 1..4 和 0，恰好覆盖 5 个非空桶。
        for (long userId : new long[]{1, 2, 3, 4, 8}) {
            seckillService.seckill(userId, VOUCHER_ID);
        }
        awaitOrderCount(5);
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
        Integer usedBuckets = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT bucket_no) FROM tb_voucher_order
                 WHERE voucher_id = ? AND stock_mode = 'BUCKET'
                """, Integer.class, VOUCHER_ID);
        assertThat(usedBuckets).isEqualTo(5);
    }

    @Test
    void bucketInitializerInitializesFreshLayoutAndRefusesHistory() {
        createBucketActivity(VOUCHER_ID, 100, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        redisTemplate.delete(RedisKeys.seckillStock(VOUCHER_ID));

        assertThat(stockInitializer.initializeSafely(VOUCHER_ID)).isTrue();
        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(100L);

        redisTemplate.delete(RedisKeys.seckillStock(VOUCHER_ID));
        jdbcTemplate.update("""
                INSERT INTO tb_voucher_order
                    (request_id, message_id, user_id, voucher_id, stock_mode,
                     bucket_no, layout_version, status)
                VALUES (?, ?, ?, ?, 'BUCKET', 0, 1, 1)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1L, VOUCHER_ID);

        assertThat(stockInitializer.initializeSafely(VOUCHER_ID)).isFalse();
        assertThat(stockService.stock(VOUCHER_ID)).isNull();
    }

    @Test
    void bucketLayoutMismatchIsRejected() {
        createBucketActivity(VOUCHER_ID, 10, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));

        SeckillOrderMessage wrongMode = bucketMessage(1L, VOUCHER_ID, 0, 1, "SINGLE");
        assertThatThrownBy(() -> orderPersistenceService.persist(wrongMode))
                .isInstanceOfSatisfying(OrderBusinessException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(OrderFailure.LAYOUT_MISMATCH));

        SeckillOrderMessage wrongBucket = bucketMessage(1L, VOUCHER_ID, 4, 1, "BUCKET");
        assertThatThrownBy(() -> orderPersistenceService.persist(wrongBucket))
                .isInstanceOfSatisfying(OrderBusinessException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(OrderFailure.LAYOUT_MISMATCH));

        SeckillOrderMessage wrongLayout = bucketMessage(1L, VOUCHER_ID, 0, 2, "BUCKET");
        assertThatThrownBy(() -> orderPersistenceService.persist(wrongLayout))
                .isInstanceOfSatisfying(OrderBusinessException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(OrderFailure.LAYOUT_MISMATCH));

        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isEqualTo(10);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
    }

    @Test
    void bucketLocalSoldOutDoesNotDeductOtherBucket() {
        createBucketActivity(VOUCHER_ID, 4, 8, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        jdbcTemplate.update("""
                UPDATE tb_seckill_voucher_bucket SET remaining_stock = 0
                 WHERE voucher_id = ?
                """, VOUCHER_ID);
        jdbcTemplate.update("""
                UPDATE tb_seckill_voucher_bucket SET remaining_stock = 1
                 WHERE voucher_id = ? AND bucket_no = 0
                """, VOUCHER_ID);

        // 用户 5 起始桶 5，最多尝试 5/6/7，不会触达仍有库存的桶 0。
        SeckillOrderMessage message = bucketMessage(5L, VOUCHER_ID, 5, 1, "BUCKET");
        assertThatThrownBy(() -> orderPersistenceService.persist(message))
                .isInstanceOfSatisfying(OrderBusinessException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(OrderFailure.LOCAL_SOLD_OUT));

        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isEqualTo(1);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
    }

    @Test
    void bucketInsertFailureRollsBackBucketDeduction() {
        createBucketActivity(VOUCHER_ID, 10, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));

        SeckillOrderMessage invalid = new SeckillOrderMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                null, VOUCHER_ID, System.currentTimeMillis(), "BUCKET", 0, 1);

        assertThatThrownBy(() -> orderPersistenceService.persist(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isEqualTo(10);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
    }

    @Test
    void bucketSkewedUsersUseBoundedCrossBucketAttempts() {
        createBucketActivity(VOUCHER_ID, 10, 3, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 10);

        // 10 个用户都从桶 0 开始，桶 0 只有 4 张；最多尝试 3 个桶：0/1/2。
        for (long userId : new long[]{3, 6, 9, 12, 15, 18, 21, 24, 27, 30}) {
            seckillService.seckill(userId, VOUCHER_ID);
        }
        awaitOrderCount(10);
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
    }

    @Test
    void bucketDuplicateMessageDoesNotDeductBucketAgain() {
        createBucketActivity(VOUCHER_ID, 1, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 1);

        // 用户 4 的起始桶为 0，桶 0 分配到了唯一一张库存。
        SeckillAcceptResponse accepted = seckillService.seckill(4L, VOUCHER_ID);
        awaitOrderCount(1);
        VoucherOrder order = orderMapper.selectByRequestId(accepted.requestId());

        SeckillOrderMessage duplicate = new SeckillOrderMessage(
                order.getMessageId(), accepted.requestId(), 4L, VOUCHER_ID,
                System.currentTimeMillis(), "BUCKET", 0, 1);
        assertThat(publisher.publish(duplicate)).isEqualTo(com.voucherx.mq.publisher.PublishResult.CONFIRMED);
        assertThat(publisher.publish(duplicate)).isEqualTo(com.voucherx.mq.publisher.PublishResult.CONFIRMED);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1));
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
    }

    @Test
    void bucketModeOneThousandUsersCompeteForOneHundredVouchers() throws Exception {
        int userCount = 1000;
        int stock = 100;
        insertUsers(userCount);
        createBucketActivity(VOUCHER_ID, stock, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, stock);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        int threads = 64;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (long userId = 1; userId <= userCount; userId++) {
                long finalUserId = userId;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        seckillService.seckill(finalUserId, VOUCHER_ID);
                        accepted.incrementAndGet();
                    } catch (BusinessException ex) {
                        if (ex.errorCode() != ErrorCode.SECKILL_SOLD_OUT) {
                            unexpected.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        unexpected.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        awaitOrderCount(stock);
        assertThat(unexpected.get()).isZero();
        assertThat(accepted.get()).isEqualTo(stock);
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(stock);
        Integer duplicateUsers = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT user_id, voucher_id, COUNT(*) AS c
                      FROM tb_voucher_order
                     WHERE voucher_id = ?
                     GROUP BY user_id, voucher_id
                    HAVING c > 1
                ) t
                """, Integer.class, VOUCHER_ID);
        assertThat(duplicateUsers).isZero();
    }

    private SeckillOrderMessage bucketMessage(long userId, long voucherId, int startBucket,
                                              int layoutVersion, String mode) {
        return new SeckillOrderMessage(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                userId, voucherId, System.currentTimeMillis(), mode, startBucket, layoutVersion);
    }

    @Test
    void oneThousandUsersCompeteForOneHundredVouchers() throws Exception {
        int userCount = 1000;
        int stock = 100;
        insertUsers(userCount);
        createActivity(VOUCHER_ID, stock, stock, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, stock);

        AtomicInteger accepted = new AtomicInteger();
        ConcurrentHashMap<ErrorCode, AtomicInteger> rejected = new ConcurrentHashMap<>();
        AtomicInteger unexpected = new AtomicInteger();

        int threads = 64;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (long userId = 1; userId <= userCount; userId++) {
                long finalUserId = userId;
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                        seckillService.seckill(finalUserId, VOUCHER_ID);
                        accepted.incrementAndGet();
                    } catch (BusinessException ex) {
                        rejected.computeIfAbsent(ex.errorCode(), k -> new AtomicInteger())
                                .incrementAndGet();
                    } catch (Exception ex) {
                        unexpected.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        awaitOrderCount(stock);

        assertThat(unexpected.get()).isZero();
        assertThat(accepted.get()).isEqualTo(stock);
        assertThat(rejected.getOrDefault(ErrorCode.SECKILL_SOLD_OUT, new AtomicInteger()).get())
                .isEqualTo(userCount - stock);

        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(stock);
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();

        Integer distinctUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID);
        assertThat(distinctUsers).isEqualTo(stock);
        Integer duplicateUsers = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT user_id, voucher_id, COUNT(*) AS c
                      FROM tb_voucher_order
                     WHERE voucher_id = ?
                     GROUP BY user_id, voucher_id
                    HAVING c > 1
                ) t
                """, Integer.class, VOUCHER_ID);
        assertThat(duplicateUsers).isZero();

        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID);
        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?",
                Integer.class, VOUCHER_ID);
        assertThat(remaining).isNotNegative();
        assertThat(total).isEqualTo(remaining + orders);
    }

    private void awaitOrderCount(int expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() ->
                        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(expected));
    }

    private void insertUsers(int count) {
        List<Object[]> args = new ArrayList<>(count);
        for (long userId = 1; userId <= count; userId++) {
            args.add(new Object[]{userId, String.format("138%08d", userId), "并发用户" + userId, ""});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO tb_user (id, phone, nick_name, icon)
                VALUES (?, ?, ?, ?)
                """, args);
    }
}

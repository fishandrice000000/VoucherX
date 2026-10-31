package com.voucherx.seckill;

import com.voucherx.AbstractIntegrationTest;
import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.mq.config.RabbitMqConfig;
import com.voucherx.mq.publisher.SeckillMessagePublisher;
import com.voucherx.order.entity.VoucherOrder;
import com.voucherx.order.mapper.VoucherOrderMapper;
import com.voucherx.order.service.OrderPersistenceService;
import com.voucherx.seckill.dto.SeckillAcceptResponse;
import com.voucherx.seckill.dto.SeckillRequestStatusResponse;
import com.voucherx.seckill.model.SeckillOrderMessage;
import com.voucherx.seckill.model.SeckillRequestStatus;
import com.voucherx.seckill.service.SeckillRequestStatusStore;
import com.voucherx.seckill.service.SeckillService;
import com.voucherx.seckill.service.SeckillStockService;
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
                existing.getMessageId(), requestId, 1L, VOUCHER_ID, System.currentTimeMillis());
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
        assertThat(stockService.reserve(VOUCHER_ID, 1L, requestId, now, begin, end)).isEqualTo(4L);

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
    void orderInsertFailureRollsBackStockDeduction() {
        createActivity(VOUCHER_ID, 3, 3, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        stockService.initializeIfAbsent(VOUCHER_ID, 3);

        SeckillOrderMessage invalid = new SeckillOrderMessage(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                null, VOUCHER_ID, System.currentTimeMillis());

        assertThatThrownBy(() -> orderPersistenceService.persist(invalid))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isEqualTo(3);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
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

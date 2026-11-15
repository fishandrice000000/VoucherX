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

/**
 * 01 库存链路的真实组件回归测试，覆盖预留、单库存/桶库存、幂等、布局和事务回滚。
 * <p>
 * 一般从
 * bucketLocalSoldOutDoesNotDeductOtherBucket、failureBeforeCommitRollsBackBucketAndInsertedOrder、
 * duplicateMessageDoesNotCreateSecondOrderOrDeductStockAgain
 * 对照源码；所有测试都继承真实隔离组件环境。
 * <p>
 * preparedMessage 是只测试 MySQL 事务的夹具：准备受理依据并隔离自动投递，不执行 Redis 预扣。
 * 并发用例只验证数量及不超卖，不给出吞吐提高或性能启用验收结论。
 *
 * @see com.voucherx.order.service.OrderPersistenceService
 * @see com.voucherx.seckill.service.SeckillStockService
 */
@org.springframework.test.context.TestPropertySource(properties = "voucherx.fault.enabled=true")
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

    @Autowired
    private com.voucherx.fulfillment.RequestLedger ledger;
    @Autowired
    private com.voucherx.fulfillment.OutboxDispatcher dispatcher;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private com.voucherx.recovery.FaultInjector faults;

    /**
     * 每个用例结束后清除故障注入状态，避免上一个用例的异常触发点影响后续断言。
     */
    @org.junit.jupiter.api.AfterEach
    void clearFaults() {
        faults.clear();
    }

    /**
     * 活动开始时间在未来，验证入口校验先拒绝，Redis 初始库存 10 不被预扣。
     */
    @Test
    void activityNotStartedIsRejectedBeforeStockReservation() {
        createUser(1);
        LocalDateTime now = LocalDateTime.now();
        createActivity(VOUCHER_ID, 10, 10, now.plusHours(1), now.plusHours(2));
        initializeRedisState(VOUCHER_ID, 10);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_NOT_STARTED));
        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(10L);
    }

    /**
     * 活动结束时间在过去，验证返回 SECKILL_ENDED，尚未进入库存预留。
     */
    @Test
    void activityEndedIsRejected() {
        createUser(1);
        LocalDateTime now = LocalDateTime.now();
        createActivity(VOUCHER_ID, 10, 10, now.minusHours(2), now.minusHours(1));
        initializeRedisState(VOUCHER_ID, 10);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_ENDED));
    }

    /**
     * 区分 Redis 库存为 0 与库存键缺失：前者是售罄，后者是库存不可用，不能把丢失状态当作可重置的总量。
     */
    @Test
    void soldOutAndMissingStockStateAreDistinguished() {
        createUser(1);
        LocalDateTime now = LocalDateTime.now();
        createActivity(VOUCHER_ID, 10, 0, now.minusHours(1), now.plusHours(1));
        initializeRedisState(VOUCHER_ID, 0);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_SOLD_OUT));

        redisTemplate.delete("voucherx:seckill:stock:" + VOUCHER_ID);
        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_STOCK_UNAVAILABLE));
    }

    /**
     * 同用户在同一活动首次受理并建单后，新一次未复用幂等键的抢券应被资格检查拒绝；总计只扣 1 份、建 1 单。
     */
    @Test
    void oneUserCanOnlyReserveOnce() {
        createUser(1);
        createActivity(VOUCHER_ID, 2, 2, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 2);

        SeckillAcceptResponse first = seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        assertThat(first.status()).isEqualTo(SeckillRequestStatus.PROCESSING);
        awaitOrderCount(1);

        assertThatThrownBy(() -> seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SECKILL_DUPLICATE));

        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(1L);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1);
    }

    /**
     * 等待异步链路产生已提交购买单，查询应返回 SUCCESS 与订单身份；同时核对两处库存均为 0，其他用户查询该请求按不存在处理。
     */
    @Test
    void asynchronousResultPrefersCommittedMysqlOrder() {
        createUser(1);
        createUser(2);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 1);

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            SeckillRequestStatusResponse status = seckillService.queryRequest(1L, accepted.requestId());
            assertThat(status.status()).isEqualTo(SeckillRequestStatus.SUCCESS);
            assertThat(status.orderId()).isNotNull();
            assertThat(status.messageId()).isNotBlank();
        });

        SeckillRequestStatusResponse status = seckillService.queryRequest(1L, accepted.requestId());
        VoucherOrder committed = orderMapper.selectById(status.orderId());
        assertThat(committed.getRequestId()).isEqualTo(accepted.requestId());
        assertThat(committed.getCompletedAtMs()).isPositive();
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();

        assertThatThrownBy(() -> seckillService.queryRequest(2L, accepted.requestId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.REQUEST_NOT_FOUND));
    }

    /**
     * 单库存已成功建单后，重发两次同 requestId/messageId 的消息，验证仍只有 1 单、MySQL 库存不再扣、状态缓存仍为 SUCCESS。
     *
     * @throws Exception
     *                       等待异步重复投递期间被中断等测试异常
     */
    @Test
    void duplicateMessageDoesNotCreateSecondOrderOrDeductStockAgain() throws Exception {
        createUser(1);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 1);

        SeckillAcceptResponse accepted = seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        awaitOrderCount(1);
        String requestId = accepted.requestId();
        var existing = orderMapper.selectByRequestId(requestId);

        SeckillOrderMessage duplicate = new SeckillOrderMessage(
                existing.getMessageId(), requestId, 1L, VOUCHER_ID,
                System.currentTimeMillis(), "SINGLE", 0, 1, 1);
        assertThat(publisher.publish(duplicate).name()).isEqualTo("CONFIRMED");
        assertThat(publisher.publish(duplicate).name()).isEqualTo("CONFIRMED");

        Thread.sleep(1200);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1);
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isZero();
        assertThat(
                redisTemplate.<String, String>opsForHash().entries(RedisKeys.seckillRequest(requestId)).get("status"))
                .isEqualTo("SUCCESS");
    }

    /**
     * 临时移除主队列绑定，验证无法路由不撤销 ACCEPTED，也不立刻回补 Redis；恢复绑定并重新调度 Outbox 后仍能建单。
     */
    @Test
    void unroutableMessageKeepsDurableAcceptanceAndReservationForRetry() {
        createUser(1);
        createActivity(VOUCHER_ID, 1, 1, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 1);
        Binding binding = new Binding(RabbitMqConfig.MAIN_QUEUE, Binding.DestinationType.QUEUE,
                RabbitMqConfig.MAIN_EXCHANGE, RabbitMqConfig.MAIN_ROUTING_KEY, null);
        rabbitAdmin.removeBinding(binding);
        SeckillAcceptResponse accepted;
        try {
            accepted = seckillService.seckill(1L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
            assertThat(accepted.status()).isEqualTo(SeckillRequestStatus.PROCESSING);
            dispatcher.dispatch();
            assertThat(ledger.find(accepted.requestId()).phase()).isEqualTo("ACCEPTED");
            assertThat(stockService.stock(VOUCHER_ID)).isZero();
        } finally {
            rabbitAdmin.declareBinding(binding);
        }
        jdbcTemplate.update("UPDATE tb_outbox SET next_at_ms=0 WHERE request_id=?", accepted.requestId());
        dispatcher.dispatch();
        awaitOrderCount(1);
    }

    /**
     * 直接调用 Redis 脚本适配层，验证预留 5→4 后连续释放两次只返还一次，最终仍为 5。
     * <p>
     * 本用例没有走 MySQL 请求关闭，只验证 Lua 的释放幂等；生产补偿调用仍必须先满足数据库关闭屏障。
     */
    @Test
    void releaseIsIdempotent() {
        createActivity(VOUCHER_ID, 5, 5, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 5);

        String requestId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long begin = now - 3_600_000L;
        long end = now + 3_600_000L;
        assertThat(stockService.reserve(VOUCHER_ID, 1L, requestId, now, begin, end, "SINGLE", 0, 1, 1)).isEqualTo(4L);

        assertThat(stockService.release(VOUCHER_ID, 1L, requestId, "TEST", 1)).isTrue();
        assertThat(stockService.release(VOUCHER_ID, 1L, requestId, "TEST", 1)).isTrue();
        assertThat(stockService.stock(VOUCHER_ID)).isEqualTo(5L);
    }

    /**
     * 直接插入两个不同请求、同用户同活动的订单，验证数据库唯一约束拒绝第二笔；本用例隔离验证最终兜底，不经过 Redis。
     */
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
        first.setStockMode("SINGLE");
        first.setBucketNo(0);
        first.setLayoutVersion(1);
        first.setCompletedAtMs(System.currentTimeMillis());
        orderMapper.insert(first);

        VoucherOrder duplicate = new VoucherOrder();
        duplicate.setRequestId(UUID.randomUUID().toString());
        duplicate.setMessageId(UUID.randomUUID().toString());
        duplicate.setUserId(1L);
        duplicate.setVoucherId(VOUCHER_ID);
        duplicate.setStatus(VoucherOrder.STATUS_CREATED);
        duplicate.setStockMode("SINGLE");
        duplicate.setBucketNo(0);
        duplicate.setLayoutVersion(1);
        duplicate.setCompletedAtMs(System.currentTimeMillis());

        assertThatThrownBy(() -> orderMapper.insert(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1);
    }

    /**
     * 直接验证终态 Lua：成功不可被失败覆盖，已释放不可被失败覆盖，失败可由数据库成功事实纠正。
     */
    @Test
    void terminalStatusUpdatesProtectSuccessAndReleasedReservations() {
        createActivity(VOUCHER_ID, 10, 10, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 10);
        long now = System.currentTimeMillis();
        for (long user = 1; user <= 3; user++) {
            String id = "terminal-" + user;
            stockService.reserve(VOUCHER_ID, user, id, now, now - 3600000, now + 3600000, "SINGLE", 0, 1, 1);
        }
        statusStore.markSuccess("terminal-1", "event-1", 123L);
        statusStore.markFailed("terminal-1", "late", "LATE");
        assertThat(redisTemplate.opsForHash().get(RedisKeys.seckillRequest("terminal-1"), "status"))
                .isEqualTo("SUCCESS");
        statusStore.markFailed("terminal-2", "event-2", "FAILED");
        statusStore.markSuccess("terminal-2", "event-2", 456L);
        assertThat(redisTemplate.opsForHash().get(RedisKeys.seckillRequest("terminal-2"), "status"))
                .isEqualTo("SUCCESS");
        assertThat(stockService.release(VOUCHER_ID, 3L, "terminal-3", "TEST", 1)).isTrue();
        statusStore.markFailed("terminal-3", "late", "LATE");
        assertThat(redisTemplate.opsForHash().get(RedisKeys.seckillRequest("terminal-3"), "status"))
                .isEqualTo("RELEASED");
        assertThat(redisTemplate.getExpire(RedisKeys.seckillRequest("terminal-3"))).isEqualTo(-1L);
    }

    /**
     * 准备真实受理依据，建单提交前注入异常，验证单库存扣减、订单插入和请求成功状态一起回滚，账本仍为 ACCEPTED。
     */
    @Test
    void failureBeforeCommitRollsBackStockAndInsertedOrder() {
        createActivity(VOUCHER_ID, 3, 3, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        var message = preparedMessage(1, VOUCHER_ID, 0, 1, "SINGLE");
        faults.arm(com.voucherx.recovery.FaultPoint.ORDER_BEFORE_COMMIT, message.requestId(), "rollback-single",
                com.voucherx.recovery.FaultInjector.Action.FAIL);
        assertThatThrownBy(() -> orderPersistenceService.persist(message))
                .isInstanceOf(com.voucherx.recovery.FaultInjector.InjectedFault.class);
        assertThat(seckillVoucherMapper.selectById(VOUCHER_ID).getStock()).isEqualTo(3);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
        assertThat(ledger.find(message.requestId()).phase()).isEqualTo("ACCEPTED");
    }

    /**
     * 总量 5 分配到 8 桶，验证前 5 桶各 1、后 3 桶为 0；挑选起点覆盖非空桶的用户，最终恰好建 5 单且总余额为 0。
     */
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

        initializeRedisState(VOUCHER_ID, 5);
        // 用户 1..4 与 8 的起始桶分别为 1..4 和 0，恰好覆盖 5 个非空桶。
        for (long userId : new long[] { 1, 2, 3, 4, 8 }) {
            seckillService.seckill(userId, VOUCHER_ID, java.util.UUID.randomUUID().toString());
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

    /**
     * 新建 100 份、4 桶的完整未消费布局可初始化 Redis；删掉 Redis 后加入历史订单，初始化器必须拒绝按原总量重建。
     */
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
                     bucket_no, layout_version, status, completed_at_ms)
                VALUES (?, ?, ?, ?, 'BUCKET', 0, 1, 1, 100)
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1L, VOUCHER_ID);

        assertThat(stockInitializer.initializeSafely(VOUCHER_ID)).isFalse();
        assertThat(stockService.stock(VOUCHER_ID)).isNull();
    }

    /**
     * 夹具分别制造模式不符、起点越界、版本不符的消息，验证在扣桶前拒绝；总余额仍为 10，没有订单。
     */
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

    /**
     * 把 4 桶编号由 0/1/2/3 改成 0/1/2/99，即使行数和总量仍正确，安全初始化及事务建单都应拒绝布局。
     */
    @Test
    void malformedBucketNumberCoverageIsRejected() {
        createBucketActivity(VOUCHER_ID, 100, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        // 行数仍为 4、总额度仍为 100，但编号为 0/1/2/99；桶 3 缺失。
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher_bucket WHERE voucher_id = ? AND bucket_no = 3",
                VOUCHER_ID);
        jdbcTemplate.update("""
                INSERT INTO tb_seckill_voucher_bucket
                    (voucher_id, bucket_no, total_stock, remaining_stock, layout_version)
                VALUES (?, 99, 25, 25, 1)
                """, VOUCHER_ID);

        assertThat(stockInitializer.initializeSafely(VOUCHER_ID)).isFalse();
        assertThat(stockService.stock(VOUCHER_ID)).isNull();

        initializeRedisState(VOUCHER_ID, 100);
        SeckillOrderMessage message = bucketMessage(1L, VOUCHER_ID, 0, 1, "BUCKET");
        assertThatThrownBy(() -> orderPersistenceService.persist(message))
                .isInstanceOfSatisfying(OrderBusinessException.class,
                        ex -> assertThat(ex.failure()).isEqualTo(OrderFailure.LAYOUT_MISMATCH));
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isEqualTo(100);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
    }

    /**
     * 构造 8 桶、仅桶 0 余量为 1 的状态，用户 5 从桶 5 起步，默认最多尝试 5/6/7。
     * <p>
     * 预期 LOCAL_SOLD_OUT，桶 0 仍保留 1，没有订单；证明本次候选耗尽不等于全局售罄，也不执行无界找桶。
     * 此测试直接准备数据库受理夹具，不是一次完整 Redis 到 MQ 的抢券。
     */
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

    /**
     * 在真实受理依据下调用分桶建单，ORDER_BEFORE_COMMIT 注入异常。
     * <p>
     * 即使事务内已扣桶并插入订单，代理回滚后桶总余额仍为 10、订单为 0；这不代表自动释放了 Redis。
     */
    @Test
    void failureBeforeCommitRollsBackBucketAndInsertedOrder() {
        createBucketActivity(VOUCHER_ID, 10, 4, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        var message = preparedMessage(1, VOUCHER_ID, 0, 1, "BUCKET");
        faults.arm(com.voucherx.recovery.FaultPoint.ORDER_BEFORE_COMMIT, message.requestId(), "rollback-bucket",
                com.voucherx.recovery.FaultInjector.Action.FAIL);
        assertThatThrownBy(() -> orderPersistenceService.persist(message))
                .isInstanceOf(com.voucherx.recovery.FaultInjector.InjectedFault.class);
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isEqualTo(10);
        assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isZero();
    }

    /**
     * 10 个用户的起点全为桶 0，桶 0 只有 4 份；允许 3 次候选覆盖全部 3 桶，最终消耗全部 10 份，验证有限跨桶回退。
     */
    @Test
    void bucketSkewedUsersUseBoundedCrossBucketAttempts() {
        createBucketActivity(VOUCHER_ID, 10, 3, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 10);

        // 10 个用户都从桶 0 开始，桶 0 只有 4 张；最多尝试 3 个桶：0/1/2。
        for (long userId : new long[] { 3, 6, 9, 12, 15, 18, 21, 24, 27, 30 }) {
            seckillService.seckill(userId, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        }
        awaitOrderCount(10);
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
    }

    /**
     * 唯一库存位于桶 0，用户 4 成功后重发相同业务消息，验证仍只一笔订单、桶余量不被重复扣减。
     */
    @Test
    void bucketDuplicateMessageDoesNotDeductBucketAgain() {
        createBucketActivity(VOUCHER_ID, 1, 4, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, 1);

        // 用户 4 的起始桶为 0，桶 0 分配到了唯一一张库存。
        SeckillAcceptResponse accepted = seckillService.seckill(4L, VOUCHER_ID, java.util.UUID.randomUUID().toString());
        awaitOrderCount(1);
        VoucherOrder order = orderMapper.selectByRequestId(accepted.requestId());

        SeckillOrderMessage duplicate = new SeckillOrderMessage(
                order.getMessageId(), accepted.requestId(), 4L, VOUCHER_ID,
                System.currentTimeMillis(), "BUCKET", 0, 1, 1);
        assertThat(publisher.publish(duplicate)).isEqualTo(com.voucherx.mq.publisher.PublishResult.CONFIRMED);
        assertThat(publisher.publish(duplicate)).isEqualTo(com.voucherx.mq.publisher.PublishResult.CONFIRMED);

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(1));
        assertThat(bucketMapper.sumRemaining(VOUCHER_ID, 1)).isZero();
        assertThat(stockService.stock(VOUCHER_ID)).isZero();
    }

    /**
     * 64 个工作线程提交 1000 个不同用户抢 100 份，验证受理 100、成单 100、无重复用户、两处余额为 0。
     * <p>
     * 用 3 桶使默认 3 次候选可覆盖所有桶，以验证卖完和不超卖；4/8 桶局部售罄另有用例。
     * 该并发正确性断言不测量单桶与分桶的等条件吞吐差值。
     *
     * @throws Exception
     *                       并发任务执行、等待或超时异常
     */
    @Test
    void bucketModeOneThousandUsersCompeteForOneHundredVouchers() throws Exception {
        int userCount = 1000;
        int stock = 100;
        insertUsers(userCount);
        // 默认最多尝试 3 个桶；使用 3 个桶保证本用例能覆盖全部桶并卖完，
        // 4/8 桶的边界和局部售罄由其他用例覆盖。
        createBucketActivity(VOUCHER_ID, stock, 3, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, stock);

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
                        seckillService.seckill(finalUserId, VOUCHER_ID, java.util.UUID.randomUUID().toString());
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

    /**
     * 构造分桶边界测试用的受理消息，委托 preparedMessage；不实际进行 Redis 预扣。
     *
     * @param userId
     *                          用例用户 ID
     * @param voucherId
     *                          用例活动 ID
     * @param startBucket
     *                          计划起始桶，可故意越界以测试拒绝
     * @param layoutVersion
     *                          用例要求的布局版本，可故意与活动不符
     * @param mode
     *                          用例模式字符串，可故意与活动不符
     * @return 具有持久化受理身份的测试消息
     */
    private SeckillOrderMessage bucketMessage(long userId, long voucherId, int startBucket,
            int layoutVersion, String mode) {
        return preparedMessage(userId, voucherId, startBucket, layoutVersion, mode);
    }

    /**
     * SINGLE 模式下 64 个线程提交 1000 个不同用户抢 100 份，验证恰有 100 次受理、900 次售罄。
     * <p>
     * 异步结束后只有 100 单、没有重复用户、两处余量为 0，数据库满足初始总量等于剩余量加订单数。
     * 此用例是正确性回归，不是性能对照实验。
     *
     * @throws Exception
     *                       并发任务执行、等待或超时异常
     */
    @Test
    void oneThousandUsersCompeteForOneHundredVouchers() throws Exception {
        int userCount = 1000;
        int stock = 100;
        insertUsers(userCount);
        createActivity(VOUCHER_ID, stock, stock, LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        initializeRedisState(VOUCHER_ID, stock);

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
                        seckillService.seckill(finalUserId, VOUCHER_ID, java.util.UUID.randomUUID().toString());
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

    /**
     * 在一个 TransactionTemplate 事务中写意图、完成受理，再把对应 Outbox 事件设为 QUARANTINED。
     * <p>
     * intent/accept 使用默认 REQUIRED，加入这个外层事务。隔离事件可防止后台消费者与本测试直接调用 persist 竞争。
     * 返回消息保持账本身份，但跳过 Redis 预扣和真实 MQ 投递，专用于验证 MySQL 布局判断及回滚。
     * 不能把此夹具的成功当作完整抢券流程已运行。
     *
     * @param user
     *                    用例用户 ID
     * @param voucher
     *                    活动 ID
     * @param bucket
     *                    要写入账本及消息的起始桶
     * @param layout
     *                    要写入账本及消息的布局版本
     * @param mode
     *                    要写入账本及消息的库存模式
     * @return 与已提交受理记录匹配的订单消息
     */
    private SeckillOrderMessage preparedMessage(long user, long voucher, int bucket, int layout, String mode) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            var request = ledger.intent(user, voucher, UUID.randomUUID().toString(), mode, bucket, layout);
            ledger.accept(request.requestId());
            jdbcTemplate.update("UPDATE tb_outbox SET state='QUARANTINED' WHERE request_id=?", request.requestId());
            return request.message();
        });
    }

    /**
     * 最多等待 60 秒，每 100 毫秒查询一次订单数，给异步投递和事务提交留出完成时间。
     *
     * @param expected
     *                     该活动期望已提交的订单总数
     */
    private void awaitOrderCount(int expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(orderMapper.countByVoucherId(VOUCHER_ID)).isEqualTo(expected));
    }

    /**
     * 批量准备 1 到 count 的不同用户，供 1000 用户争抢的并发正确性用例使用；测试准备允许独立使用 JdbcTemplate。
     *
     * @param count
     *                  要准备的用户数量
     */
    private void insertUsers(int count) {
        List<Object[]> args = new ArrayList<>(count);
        for (long userId = 1; userId <= count; userId++) {
            args.add(new Object[] { userId, String.format("138%08d", userId), "并发用户" + userId, "" });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO tb_user (id, phone, nick_name, icon)
                VALUES (?, ?, ?, ?)
                """, args);
    }
}

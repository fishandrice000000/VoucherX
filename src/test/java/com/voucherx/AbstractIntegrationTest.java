package com.voucherx;

import com.voucherx.mq.config.RabbitMqConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试统一使用真实 MySQL、Redis、RabbitMQ。容器只启动一次，由 JVM 退出时清理。
 */
@org.junit.jupiter.api.Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 每个测试类结束后关闭上下文，避免多个上下文同时监听同一个 RabbitMQ 队列导致消息被旧消费者抢走。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("voucherx")
            .withUsername("voucherx")
            .withPassword("voucherx")
            .withInitScript("db/schema.sql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-alpine"));

    static {
        MYSQL.start();
        REDIS.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected StringRedisTemplate redisTemplate;
    @Autowired
    protected RedisConnectionFactory redisConnectionFactory;
    @Autowired
    protected RabbitAdmin rabbitAdmin;

    @BeforeEach
    void cleanInfrastructureState() {
        purgeQueues();
        jdbcTemplate.update("DELETE FROM tb_recovery_run");
        jdbcTemplate.update("DELETE FROM tb_repair_audit");
        jdbcTemplate.update("DELETE FROM tb_compensation");
        jdbcTemplate.update("DELETE FROM tb_coupon_action");
        jdbcTemplate.update("DELETE FROM tb_payment_event");
        jdbcTemplate.update("DELETE FROM tb_consumption_order");
        jdbcTemplate.update("DELETE FROM tb_user_coupon");
        jdbcTemplate.update("DELETE FROM tb_outbox");
        jdbcTemplate.update("DELETE FROM tb_seckill_request");
        jdbcTemplate.update("DELETE FROM tb_activity_control");
        jdbcTemplate.update("DELETE FROM tb_voucher_order");
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher_bucket");
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher");
        jdbcTemplate.update("DELETE FROM tb_voucher");
        jdbcTemplate.update("DELETE FROM tb_shop");
        jdbcTemplate.update("DELETE FROM tb_user");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private void purgeQueues() {
        try {
            rabbitAdmin.purgeQueue(RabbitMqConfig.MAIN_QUEUE, false);
            rabbitAdmin.purgeQueue(RabbitMqConfig.DEAD_QUEUE, false);
        } catch (Exception ignored) {
            // 队列尚未声明时忽略；应用启动后 RabbitAdmin 会自动声明。
        }
    }

    /**
     * 为直接验证 Lua 或构造已消费场景准备明确的门禁和库存；不替代生产安全初始化测试。
     *
     * @param voucherId
     *                      活动 ID
     * @param remaining
     *                      测试需要的 Redis 余额
     * @return 缺失库存键本次是否创建成功
     */
    protected boolean initializeRedisState(long voucherId, int remaining) {
        jdbcTemplate.update("INSERT IGNORE INTO tb_activity_control(voucher_id) VALUES (?)", voucherId);
        redisTemplate.opsForHash().putAll(com.voucherx.common.redis.RedisKeys.seckillGate(voucherId),
                java.util.Map.of("state", "NORMAL", "epoch", "1"));
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                com.voucherx.common.redis.RedisKeys.seckillStock(voucherId), String.valueOf(remaining)));
    }

    protected void createUser(long userId) {
        jdbcTemplate.update("""
                INSERT INTO tb_user (id, phone, nick_name, icon)
                VALUES (?, ?, ?, '')
                """,
                userId, String.format("138%08d", userId), "测试用户" + userId);
    }

    /**
     * 只在隔离测试数据库中准备 SINGLE 活动及券模板，版本 1、桶数 1。
     * <p>
     * 券模板买价 100 分、面额 1000 分；当前库存可小于总库存以构造已消耗场景。
     * 本方法不初始化 Redis，不是生产商家创建活动接口。
     *
     * @param voucherId
     *                       模板及活动共用的 ID
     * @param totalStock
     *                       初始总量
     * @param stock
     *                       当前 MySQL 单库存余额
     * @param begin
     *                       活动开始本地日期时间
     * @param end
     *                       活动结束本地日期时间
     */
    protected void createActivity(long voucherId, int totalStock, int stock,
            java.time.LocalDateTime begin, java.time.LocalDateTime end) {
        jdbcTemplate.update("""
                INSERT INTO tb_voucher
                    (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
                VALUES (?, 1, ?, '', '', 100, 1000, 1, 1)
                """,
                voucherId, "测试秒杀券-" + voucherId);
        jdbcTemplate.update("""
                INSERT INTO tb_seckill_voucher
                    (voucher_id, total_stock, stock, stock_mode, bucket_count,
                     layout_version, begin_time, end_time)
                VALUES (?, ?, ?, 'SINGLE', 1, 1, ?, ?)
                """,
                voucherId, totalStock, stock, begin, end);
    }

    /**
     * 在隔离测试数据库创建 BUCKET 活动和版本 1 的连续桶布局，不写 Redis。
     * <p>
     * 先按 totalStock/bucketCount 平分，再把余数依次分给较小桶号；总量 5、8 桶时得到 1/1/1/1/1/0/0/0。
     * 每桶初始量和剩余量相等，合计保持活动总量，活动表 stock 固定 0，因为权威余量在桶表。
     * <p>
     * 这是测试数据准备规则，不是生产自动分桶服务；测试随后显式初始化 Redis 或调用安全初始化器。
     *
     * @param voucherId
     *                        模板及活动共用的 ID
     * @param totalStock
     *                        要分配的总库存
     * @param bucketCount
     *                        桶数量，调用者提供正数
     * @param begin
     *                        活动开始本地日期时间
     * @param end
     *                        活动结束本地日期时间
     */
    protected void createBucketActivity(long voucherId, int totalStock, int bucketCount,
            java.time.LocalDateTime begin, java.time.LocalDateTime end) {
        jdbcTemplate.update("""
                INSERT INTO tb_voucher
                    (id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status)
                VALUES (?, 1, ?, '', '', 100, 1000, 1, 1)
                """,
                voucherId, "测试分桶秒杀券-" + voucherId);
        jdbcTemplate.update("""
                INSERT INTO tb_seckill_voucher
                    (voucher_id, total_stock, stock, stock_mode, bucket_count,
                     layout_version, begin_time, end_time)
                VALUES (?, ?, 0, 'BUCKET', ?, 1, ?, ?)
                """,
                voucherId, totalStock, bucketCount, begin, end);
        int base = totalStock / bucketCount;
        int remainder = totalStock % bucketCount;
        for (int bucketNo = 0; bucketNo < bucketCount; bucketNo++) {
            int amount = base + (bucketNo < remainder ? 1 : 0);
            jdbcTemplate.update("""
                    INSERT INTO tb_seckill_voucher_bucket
                        (voucher_id, bucket_no, total_stock, remaining_stock, layout_version)
                    VALUES (?, ?, ?, ?, 1)
                    """,
                    voucherId, bucketNo, amount, amount);
        }
    }
}

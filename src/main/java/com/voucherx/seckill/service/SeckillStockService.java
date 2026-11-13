package com.voucherx.seckill.service;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.config.SeckillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

/**
 * Redis 秒杀预留的脚本适配层；库存、用户资格、请求状态和归属证据由 Lua 一次原子处理。
 * <p>
 * 这里的原子性只覆盖一次 Redis 脚本，不覆盖 MySQL 事务或 RabbitMQ。
 * 主线读 reserve，并对照 resources/lua/seckill_reserve.lua；数据库最终扣库存发生在异步建单事务。
 */
@Service
public class SeckillStockService {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockService.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> seckillReserveScript;
    private final DefaultRedisScript<Long> seckillReleaseScript;

    public SeckillStockService(StringRedisTemplate redisTemplate,
            @Qualifier("seckillReserveScript") DefaultRedisScript<Long> seckillReserveScript,
            @Qualifier("seckillReleaseScript") DefaultRedisScript<Long> seckillReleaseScript) {
        this.redisTemplate = redisTemplate;
        this.seckillReserveScript = seckillReserveScript;
        this.seckillReleaseScript = seckillReleaseScript;
    }

    /**
     * 兼容入口，按初始恢复代次 1 执行预留；正常抢券链路使用显式传入代次的重载。
     *
     * @param voucherId
     *                          活动 ID
     * @param userId
     *                          已认证用户 ID
     * @param requestId
     *                          已提交 MySQL 意图的请求 ID
     * @param nowMillis
     *                          编排层取得的当前 Unix 毫秒
     * @param beginMillis
     *                          活动开始 Unix 毫秒
     * @param endMillis
     *                          活动结束 Unix 毫秒
     * @param stockMode
     *                          SINGLE 或 BUCKET
     * @param startBucketNo
     *                          计划起始桶
     * @param layoutVersion
     *                          本次请求绑定的库存布局版本
     * @return Lua 返回值：非负表示成功；负数表示明确拒绝
     */
    public long reserve(Long voucherId, Long userId, String requestId,
            long nowMillis, long beginMillis, long endMillis,
            String stockMode, int startBucketNo, int layoutVersion) {
        return reserve(voucherId, userId, requestId, nowMillis, beginMillis, endMillis, stockMode, startBucketNo,
                layoutVersion, 1);
    }

    /**
     * 把 Redis 操作位置放入 KEYS，把请求身份和业务条件按顺序放入 ARGV，执行一次预留脚本。
     * <p>
     * 五个键的顺序不能交换：
     * <ol>
     * <li>stock：活动总库存字符串，两种 MySQL 库存模式都共用它。</li>
     * <li>users：已占资格的用户 Set。</li>
     * <li>request：本次请求 Hash，写入身份、PENDING、布局和代次。</li>
     * <li>owners：userId 到 requestId 的归属 Hash。</li>
     * <li>gate：活动门禁 state/epoch。</li>
     * </ol>
     * <p>
     * ARGV[1..10] 依次是 userId、requestId、nowMillis、beginMillis、endMillis、voucherId、
     * stockMode、startBucketNo、layoutVersion、epoch。Java 转字符串传输，Lua 按需 tonumber。
     * <p>
     * 脚本先检查门禁，再识别同请求重放，再检查库存键、时间、资格及库存。
     * 通过后原子 DECR、SADD、写归属和请求状态，并 PERSIST 活跃请求；不会替 MySQL 提交事务。
     * 同一请求已拥有预留时只返回当前余量，不再扣减；Redis 不在这里选择实际 MySQL 扣减桶。
     * <p>
     * 非负结果为成功，0 代表余量恰好为零；负数为 -1 库存不可用、-2 未开始、-3 已结束、
     * -4 重复用户、-5 售罄、-6 门禁或代次不符。连接异常或空返回抛业务异常，调用者须按结果可能未知处理。
     *
     * @param voucherId
     *                          活动 ID
     * @param userId
     *                          已认证用户 ID
     * @param requestId
     *                          已提交 MySQL 意图的请求 ID
     * @param nowMillis
     *                          编排层取得的当前 Unix 毫秒
     * @param beginMillis
     *                          活动开始 Unix 毫秒
     * @param endMillis
     *                          活动结束 Unix 毫秒
     * @param stockMode
     *                          SINGLE 或 BUCKET
     * @param startBucketNo
     *                          计划起始 MySQL 桶号；只是随请求记录，不用于 Redis 分桶
     * @param layoutVersion
     *                          绑定的库存布局版本
     * @param epoch
     *                          意图中的活动恢复代次
     * @return 预扣后的或同请求重放时观察到的当前余量；负数为明确拒绝码
     * @see RedisKeys
     * @see com.voucherx.config.RedisScriptConfig#seckillReserveScript()
     */
    public long reserve(Long voucherId, Long userId, String requestId,
            long nowMillis, long beginMillis, long endMillis,
            String stockMode, int startBucketNo, int layoutVersion, long epoch) {
        try {
            Long result = redisTemplate.execute(
                    seckillReserveScript,
                    List.of(RedisKeys.seckillStock(voucherId),
                            RedisKeys.seckillUsers(voucherId),
                            RedisKeys.seckillRequest(requestId),
                            RedisKeys.seckillOwners(voucherId), RedisKeys.seckillGate(voucherId)),
                    String.valueOf(userId),
                    requestId,
                    String.valueOf(nowMillis),
                    String.valueOf(beginMillis),
                    String.valueOf(endMillis),
                    String.valueOf(voucherId),
                    stockMode,
                    String.valueOf(startBucketNo),
                    String.valueOf(layoutVersion), String.valueOf(epoch));
            if (result == null) {
                throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE);
            }
            return result;
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.SECKILL_STOCK_UNAVAILABLE, "Redis 库存操作失败", ex);
        }
    }

    /**
     * 兼容入口，以初始恢复代次 1 调用释放脚本；仍须先满足数据库关闭屏障。
     *
     * @param voucherId
     *                      活动 ID
     * @param userId
     *                      原预留用户 ID
     * @param requestId
     *                      原预留请求 ID
     * @param reason
     *                      释放原因
     * @return 脚本完成释放或确认已释放时为 true
     */
    public boolean release(Long voucherId, Long userId, String requestId, String reason) {
        return release(voucherId, userId, requestId, reason, 1);
    }

    /**
     * 05 的 Redis 释放适配层；调用前必须已提交 MySQL 关闭屏障，且当前持有活动/请求/补偿保护锁。
     * <p>
     * KEYS 顺序为 stock、users、request、owners、gate；ARGV 为
     * userId、reason、requestId、epoch，没有 compensationId。
     * 脚本检查门禁（缺失时按 NORMAL/1）、库存/请求键存在，以及请求 Hash 的 requestId、userId、epoch。
     * <p>
     * 随后原请求 status=RELEASED 直接返回 2，不再次删 owners 或 INCR；status=SUCCESS 拒绝。
     * 尚未释放时才要求 owners[userId] 仍指向原请求且库存数值合法，再原子移除归属/资格、加回 1、写 RELEASED 并 PERSIST
     * 请求证据。
     * 例如旧 R1 已释放而用户新 R2 已占资格，R1 的重复释放不会清掉 R2 的新归属。
     * <p>
     * 返回 1（本次释放）与 2（之前已释放）都映射 true；负码、空返回或捕获的 Redis 数据访问异常为 false。
     * false 不证明“没发生过释放”，也不许可盲目补加；调用方保留持久进度再试或隔离。
     * 本方法不关闭数据库请求，也不改变 MySQL 活动库存。
     *
     * @param voucherId
     *                      原活动 ID
     * @param userId
     *                      原预留用户 ID
     * @param requestId
     *                      原请求 ID，重试保持不变
     * @param reason
     *                      新释放时保存的原因
     * @param epoch
     *                      与当前请求及活动核对的恢复代次
     * @return 已达到该请求释放结果时为 true，否则由调用方重试或隔离
     * @see com.voucherx.reconcile.CompensationService#execute
     * @see com.voucherx.common.redis.RedisKeys
     */
    public boolean release(Long voucherId, Long userId, String requestId, String reason, long epoch) {
        try {
            Long result = redisTemplate.execute(
                    seckillReleaseScript,
                    List.of(RedisKeys.seckillStock(voucherId),
                            RedisKeys.seckillUsers(voucherId),
                            RedisKeys.seckillRequest(requestId),
                            RedisKeys.seckillOwners(voucherId), RedisKeys.seckillGate(voucherId)),
                    String.valueOf(userId), reason, requestId, String.valueOf(epoch));
            return result != null && (result == 1L || result == 2L);
        } catch (DataAccessException ex) {
            log.error("释放 Redis 秒杀预扣失败，requestId={}", requestId, ex);
            return false;
        }
    }

    /**
     * 通过 SET NX 仅初始化缺失的 Redis 总库存，不覆盖已有值。
     * <p>
     * 本方法不检查数据库历史，调用者须先由安全初始化器确认不会重置已有占用。
     *
     * @param voucherId
     *                      活动 ID
     * @param stock
     *                      已经核验可初始化的库存数量
     * @return 本次成功创建键时为 true；键已存在或操作失败时为 false
     */
    public boolean initializeIfAbsent(Long voucherId, int stock) {
        try {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(RedisKeys.seckillStock(voucherId), String.valueOf(stock));
            return Boolean.TRUE.equals(created);
        } catch (DataAccessException ex) {
            log.warn("初始化 Redis 秒杀库存失败，voucherId={}", voucherId, ex);
            return false;
        }
    }

    /**
     * 检查 Redis 库存键是否存在；连接异常按 false 返回，后续初始化仍受数据库安全检查约束。
     *
     * @param voucherId
     *                      活动 ID
     * @return 观察到库存键存在时为 true
     */
    public boolean exists(Long voucherId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.seckillStock(voucherId)));
        } catch (DataAccessException ex) {
            return false;
        }
    }

    /**
     * 读取 Redis 剩余库存以便观察；该值不能代替 MySQL 订单或付款事实。
     *
     * @param voucherId
     *                      活动 ID
     * @return 剩余库存；键缺失时为 {@code null}
     */
    public Long stock(Long voucherId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.seckillStock(voucherId));
        return value == null ? null : Long.valueOf(value);
    }
}

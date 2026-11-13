package com.voucherx.recovery;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import com.voucherx.common.redis.RedisKeys;
import com.voucherx.fulfillment.RequestRecord;
import com.voucherx.reconcile.RepairAudit;
import com.voucherx.voucher.mapper.SeckillVoucherBucketMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.voucherx.recovery.mapper.RecoveryMapper;
import com.voucherx.voucher.mapper.SeckillVoucherMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Consumer;

@Service
public class RecoveryService {
    public record Control(long voucherId, String state, long epoch, String recoveryId) {
    }

    private final RecoveryMapper mapper;
    private final SeckillVoucherMapper activities;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;
    private final RepairAudit audit;
    private final Clock clock;
    private final RecoveryProperties config;
    private final SeckillVoucherBucketMapper buckets;
    private final DefaultRedisScript<Long> publish;
    private final FaultInjector faults;

    public RecoveryService(RecoveryMapper mapper, SeckillVoucherMapper activities, StringRedisTemplate redis,
            PlatformTransactionManager manager,
            RepairAudit audit, Clock clock, RecoveryProperties config, SeckillVoucherBucketMapper buckets,
            @Qualifier("recoveryPublishScript") DefaultRedisScript<Long> publish, FaultInjector faults) {
        this.mapper = mapper;
        this.activities = activities;
        this.redis = redis;
        this.tx = new TransactionTemplate(manager);
        this.audit = audit;
        this.clock = clock;
        this.config = config;
        this.buckets = buckets;
        this.publish = publish;
        this.faults = faults;
    }

    public Control status(long voucher) {
        Control control = mapper.findControl(voucher);
        if (control == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动控制记录不存在");
        return control;
    }

    public Control freeze(long voucher, String reason) {
        Control frozen = tx.execute(status -> {
            if (mapper.countActivity(voucher) != 1)
                throw new BusinessException(ErrorCode.SECKILL_NOT_FOUND);
            mapper.ensureControl(voucher);
            Control c = lock(voucher);
            if (!"NORMAL".equals(c.state()))
                return c;
            String id = UUID.randomUUID().toString();
            long epoch = Math.addExact(c.epoch(), 1);
            long now = clock.millis();
            mapper.freeze(voucher, epoch, id, reason);
            mapper.insertRun(id, voucher, epoch, now);
            audit.record(null, voucher, "FREEZE", "oldEpoch=" + c.epoch() + ",reason=" + reason, "epoch=" + epoch);
            return new Control(voucher, "FROZEN", epoch, id);
        });
        // DB 屏障先成立；旧 Redis 预扣随后被新代次 gate 拒绝。期间所有意图仍可扫描。
        frozen = tx.execute(status -> {
            Control current = lock(voucher);
            if (!"NORMAL".equals(current.state()))
                ensureFrozen(current);
            return current;
        });
        faults.hit(FaultPoint.RECOVERY_FROZEN, String.valueOf(voucher), frozen.recoveryId());
        return frozen;
    }

    public Control recover(long voucher) {
        Control c = tx.execute(status -> {
            Control current = lock(voucher);
            if (!"NORMAL".equals(current.state()))
                ensureFrozen(current);
            return current;
        });
        if ("NORMAL".equals(c.state()))
            return c;
        if ("FROZEN".equals(c.state())) {
            tx.executeWithoutResult(status -> plan(lock(voucher)));
            faults.hit(FaultPoint.RECOVERY_PLANNED, String.valueOf(voucher), c.recoveryId());
        }
        if ("RECONCILING".equals(status(voucher).state()))
            tx.executeWithoutResult(status -> rebuild(lock(voucher)));
        if ("REBUILDING".equals(status(voucher).state()))
            tx.executeWithoutResult(status -> resume(lock(voucher)));
        return status(voucher);
    }

    private Control lock(long voucher) {
        Control control = mapper.lockControl(voucher);
        if (control == null)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return control;
    }

    private void ensureFrozen(Control c) {
        redis.opsForHash().putAll(RedisKeys.seckillGate(c.voucherId()),
                Map.of("state", "FROZEN", "epoch", String.valueOf(c.epoch())));
    }

    private void plan(Control c) {
        if (!"FROZEN".equals(c.state()))
            return;
        ensureFrozen(c);
        requests(c.voucherId(), r -> {
            long orderCount = mapper.countMatchingOrders(r.requestId(), c.voucherId(), r.userId());
            if ("SUCCEEDED".equals(r.phase()) && orderCount != 1 || !"SUCCEEDED".equals(r.phase()) && orderCount != 0)
                throw new IllegalStateException("订单与请求终态冲突: " + r.requestId());
            String phase = r.phase(), reservation = r.reservation(), reason = r.reason();
            if ("INTENT".equals(phase)) {
                var evidence = redis.opsForHash().entries(RedisKeys.seckillRequest(r.requestId()));
                boolean owned = r.requestId().equals(
                        redis.opsForHash().get(RedisKeys.seckillOwners(c.voucherId()), String.valueOf(r.userId())))
                        && String.valueOf(r.epoch()).equals(evidence.get("epoch"))
                        && String.valueOf(r.userId()).equals(evidence.get("userId"))
                        && String.valueOf(r.voucherId()).equals(evidence.get("voucherId"));
                // 未持久化受理的意图，无肯定证据时关闭；新代次已封住旧预扣，不会迟到污染。
                phase = owned ? "ACCEPTED" : "CLOSED";
                reservation = owned ? "RESERVED" : "NONE";
                reason = owned ? "RECOVERY_ACCEPTED" : "RECOVERY_NOT_ACCEPTED";
            }
            if ("ACCEPTED".equals(phase) && !"RESERVED".equals(reservation))
                throw new IllegalStateException("受理请求缺少预留事实");
            mapper.rebindRequest(r.requestId(), phase, reservation, reason, c.epoch(), clock.millis());
            if ("ACCEPTED".equals(phase)) {
                mapper.rebindEvent(r.eventId(), r.requestId(), clock.millis());
            } else
                mapper.completeEvent(r.requestId());
        });
        long duplicate = mapper.duplicateReservations(c.voucherId());
        long conflict = mapper.conflictingReservations(c.voucherId());
        if (duplicate != 0 || conflict != 0)
            throw new IllegalStateException("用户唯一预留事实冲突");
        RecoveryPlan plan = currentPlan(c.voucherId());
        mapper.savePlan(c.recoveryId(), plan, clock.millis());
        transition(c, "RECONCILING");
        audit.record(null, c.voucherId(), "PLAN_RECOVERY", plan.toString(), "RECONCILING");
    }

    private RecoveryPlan currentPlan(long voucher) {
        var activity = activities.selectById(voucher);
        if (activity == null)
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        long remaining;
        if ("BUCKET".equals(activity.getStockMode())) {
            int layout = activity.getLayoutVersion();
            int count = activity.getBucketCount();
            var stats = buckets.selectLayoutStats(voucher, layout);
            if (count < 1 || stats == null || !stats.covers(count)
                    || buckets.sumTotal(voucher, layout) != activity.getTotalStock())
                throw new IllegalStateException("MySQL 库存桶布局不完整");
            remaining = buckets.sumRemaining(voucher, layout);
        } else if ("SINGLE".equals(activity.getStockMode()))
            remaining = activity.getStock();
        else
            throw new IllegalStateException("未知库存模式");
        long confirmed = mapper.countConfirmed(voucher);
        long reserved = mapper.countReserved(voucher);
        return RecoveryPlan.calculate(activity.getTotalStock(), remaining, confirmed, reserved);
    }

    private void rebuild(Control c) {
        if (!"RECONCILING".equals(c.state()))
            return;
        ensureFrozen(c);
        RecoveryPlan plan = currentPlan(c.voucherId());
        if (!plan.equals(savedPlan(c.recoveryId())))
            throw new IllegalStateException("恢复快照已经变化");
        String prefix = "voucherx:recovery:" + c.recoveryId();
        String stageStock = prefix + ":stock", stageUsers = prefix + ":users", stageOwners = prefix + ":owners";
        redis.delete(List.of(stageStock, stageUsers, stageOwners));
        redis.opsForValue().set(stageStock, String.valueOf(plan.available()));
        long after = 0;
        int seen = 0;
        while (true) {
            var page = mapper.pageOrders(c.voucherId(), after, config.pageSize());
            if (page.isEmpty())
                break;
            for (var order : page) {
                if (++seen > config.maxRecords())
                    throw new IllegalStateException("恢复记录超出配置预算");
                String user = String.valueOf(order.userId()), id = order.requestId();
                redis.opsForSet().add(stageUsers, user);
                redis.opsForHash().put(stageOwners, user, id);
                redis.opsForHash().putAll(RedisKeys.seckillRequest(id),
                        Map.of("requestId", id, "userId", user, "voucherId", String.valueOf(c.voucherId()),
                                "epoch", String.valueOf(c.epoch()), "status", "SUCCESS", "orderId",
                                String.valueOf(order.id()), "messageId", order.messageId()));
                after = order.id();
            }
        }
        requests(c.voucherId(), r -> {
            if ("ACCEPTED".equals(r.phase())) {
                String user = String.valueOf(r.userId());
                redis.opsForSet().add(stageUsers, user);
                redis.opsForHash().put(stageOwners, user, r.requestId());
            }
            String status = switch (r.phase()) {
                case "SUCCEEDED" -> "SUCCESS";
                case "ACCEPTED" -> "PROCESSING";
                default -> "RELEASED";
            };
            Map<String, String> snapshot = new HashMap<>(Map.of("requestId", r.requestId(), "userId",
                    String.valueOf(r.userId()), "voucherId", String.valueOf(r.voucherId()),
                    "epoch", String.valueOf(c.epoch()), "status", status, "messageId", r.eventId(), "stockMode",
                    r.stockMode(), "startBucketNo", String.valueOf(r.startBucketNo()), "layoutVersion",
                    String.valueOf(r.layoutVersion())));
            if (r.orderId() != null)
                snapshot.put("orderId", String.valueOf(r.orderId()));
            redis.opsForHash().putAll(RedisKeys.seckillRequest(r.requestId()), snapshot);
            redis.persist(RedisKeys.seckillRequest(r.requestId()));
        });
        Long result = redis.execute(publish,
                List.of(RedisKeys.seckillStock(c.voucherId()), RedisKeys.seckillUsers(c.voucherId()),
                        RedisKeys.seckillOwners(c.voucherId()),
                        RedisKeys.seckillGate(c.voucherId()), stageStock, stageUsers, stageOwners),
                String.valueOf(c.epoch()), c.recoveryId(), String.valueOf(plan.available()),
                String.valueOf(plan.reserved() + plan.confirmed()));
        if (result == null || result != 1)
            throw new IllegalStateException("Redis 恢复发布校验失败: " + result);
        faults.hit(FaultPoint.RECOVERY_PUBLISHED, String.valueOf(c.voucherId()), c.recoveryId());
        // 发布后仍冻结；再次执行只会按同一权威快照覆盖，不做增量加库存。
        mapper.releaseClosedRequests(c.voucherId(), clock.millis());
        mapper.releaseCompensations(c.voucherId(), clock.millis());
        transition(c, "REBUILDING");
        audit.record(null, c.voucherId(), "REBUILD", plan.toString(), "PUBLISHED_FROZEN");
    }

    private void resume(Control c) {
        if (!"REBUILDING".equals(c.state()))
            return;
        RecoveryPlan plan = savedPlan(c.recoveryId());
        if (!plan.equals(currentPlan(c.voucherId())))
            throw new IllegalStateException("复流前权威库存发生变化");
        var gate = redis.opsForHash().entries(RedisKeys.seckillGate(c.voucherId()));
        if (!String.valueOf(c.epoch()).equals(gate.get("epoch"))
                || !c.recoveryId().equals(gate.get("publishedRecovery"))
                || !String.valueOf(plan.available())
                        .equals(redis.opsForValue().get(RedisKeys.seckillStock(c.voucherId())))
                || !Long.valueOf(plan.reserved() + plan.confirmed())
                        .equals(redis.opsForSet().size(RedisKeys.seckillUsers(c.voucherId())))
                || !Long.valueOf(plan.reserved() + plan.confirmed())
                        .equals(redis.opsForHash().size(RedisKeys.seckillOwners(c.voucherId()))))
            throw new IllegalStateException("Redis 恢复内容不完整，禁止复流");
        long unmatched = mapper.countUnmatchedEvents(c.voucherId(), c.epoch());
        if (unmatched != 0)
            throw new IllegalStateException("有效请求未安排恢复投递");
        faults.hit(FaultPoint.RECOVERY_BEFORE_RESUME, String.valueOf(c.voucherId()), c.recoveryId());
        redis.opsForHash().put(RedisKeys.seckillGate(c.voucherId()), "state", "NORMAL");
        mapper.resume(c.voucherId(), c.epoch());
        mapper.setRunState(c.recoveryId(), "DONE", clock.millis());
        audit.record(null, c.voucherId(), "RESUME", plan.toString(), "NORMAL");
    }

    private RecoveryPlan savedPlan(String id) {
        var plan = mapper.savedPlan(id);
        if (plan == null)
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        return plan;
    }

    private void transition(Control c, String state) {
        mapper.setActivityState(c.voucherId(), c.epoch(), state);
        mapper.setRunState(c.recoveryId(), state, clock.millis());
    }

    private void requests(long voucher, Consumer<RequestRecord> consumer) {
        String after = "";
        int seen = 0;
        while (true) {
            var page = mapper.pageRequests(voucher, after, config.pageSize());
            if (page.isEmpty())
                return;
            for (var r : page) {
                if (++seen > config.maxRecords())
                    throw new IllegalStateException("恢复请求超出配置预算");
                consumer.accept(r);
                after = r.requestId();
            }
        }
    }
}

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

/**
 * 06 活动冻结与恢复的编排入口：先阻止发行状态变化，再根据 MySQL 事实重建 Redis，校验后复流。
 * <p>
 * 阅读顺序为 {@link #freeze(long, String)} → {@link #recover(long)} → plan → rebuild
 * → resume。
 * 活动控制行使用独占锁，与正常链路的活动共享锁互斥；epoch 递增后，旧请求和旧消息不能继续按旧身份写入。
 * 这里冻结的是受理、建单和补偿等发行链路，支付与核销不改变已发行订单数，仍可独立推进。
 * <p>
 * 以总额 100、数据库余量 80、已建单 20、在途受理 5 为例，恢复出的 Redis 可用额度为 75，而非 80。
 * 库存、用户资格 Set、归属 Hash 经 Lua 一次替换；请求 Hash 逐条重建，MySQL 与 Redis 分别提交。
 * 因此阶段失败时必须按持久化状态重入，不能把 Redis 写入当成数据库事务的一部分。
 * <p>
 * TransactionTemplate 使用默认 REQUIRED 传播：维护入口没有外层事务时，各阶段分别提交。
 * 若外层另包事务，不能再假定这些调用之间已有独立提交的恢复检查点。
 *
 * @see RecoveryPlan
 * @see com.voucherx.fulfillment.ActivityGate
 * @see com.voucherx.fulfillment.RequestLedger
 */
@Service
public class RecoveryService {
    /**
     * 活动控制行的状态快照；读取到的 NORMAL 不是可长期使用的写入许可。
     * <p>
     * 同一恢复任务的阶段依次为 FROZEN、RECONCILING、REBUILDING，最终活动回到 NORMAL，恢复任务记为 DONE。
     *
     * @param voucherId
     *                       活动 ID
     * @param state
     *                       数据库活动控制状态
     * @param epoch
     *                       当前恢复代次，冻结新任务时递增
     * @param recoveryId
     *                       恢复任务 ID；首次冻结前可以为空，复流后仍保留
     */
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

    /**
     * 无行锁读取活动控制状态，供维护入口展示和恢复编排判断下一步。
     * <p>
     * 该快照不保护后续操作；真正推进阶段时必须重新取得独占锁并检查阶段。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 当前控制快照
     * @throws BusinessException
     *                               活动控制记录不存在
     */
    public Control status(long voucher) {
        Control control = mapper.findControl(voucher);
        if (control == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "活动控制记录不存在");
        return control;
    }

    /**
     * 建立数据库冻结屏障，并把当前代次的冻结门禁写入 Redis。
     * <p>
     * 第一个事务先确认活动存在，再独占锁定控制行；只有 NORMAL 才创建恢复 ID、递增 epoch，
     * 并同时保存 FROZEN 状态、恢复任务和审计。重复冻结复用已有任务，不再次增加代次。
     * 已持有活动共享锁的事务先结束；之后正常链路不能越过数据库状态检查。
     * <p>
     * 第二个事务重新取得控制行独占锁，再写 Redis 的 FROZEN/epoch；它会读取并发恢复后的最新状态。
     * 两次写入之间不是跨库原子操作：即使 Redis 门禁尚未更新，数据库屏障也已限制持久化受理与建单。
     * 随后 {@link #recover(long)} 扫描留下的意图并重建，而不是相信旧 Redis 库存。
     * <p>
     * RECOVERY_FROZEN 故障点位于上述事务回调返回之后；维护入口无外层事务时，冻结已提交。
     *
     * @param voucher
     *                    要冻结的活动 ID
     * @param reason
     *                    冻结原因，写入控制行和审计
     *
     * @return 重新读取的当前控制状态，可能已被其他恢复调用推进
     * @throws BusinessException
     *                                 活动不存在
     * @throws ArithmeticException
     *                                 恢复代次递增溢出
     */
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

    /**
     * 从数据库保存的阶段继续一次恢复；已经 NORMAL 时直接返回，不创建新的冻结任务。
     * <p>
     * 先在独占锁内补齐 Redis 冻结门禁；随后按阶段调用 plan、rebuild、resume。
     * 每一阶段重新加锁并再次核对状态，避免用入口处的旧快照直接修改。
     * 在无外层事务的维护调用中，已完成阶段保留提交结果，失败阶段的数据库修改回滚。
     * <p>
     * 例如 Redis 已发布但数据库仍为 RECONCILING 时，下一次会再次按同一快照覆盖发布；
     * REBUILDING 表示发布阶段已提交，下一次只需进行复流校验。
     * 事实不一致、Redis 异常或预算超限会抛出异常，调用者应排查后重试，不应绕过校验强行复流。
     *
     * @param voucher
     *                    已冻结活动的 ID
     *
     * @return 本次推进结束后的数据库控制快照
     * @see #freeze(long, String)
     * @see #rebuild(Control)
     * @see #resume(Control)
     */
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

    /**
     * 在调用者事务中用 FOR UPDATE 取得活动控制行独占锁。
     * <p>
     * 它与正常链路的 FOR SHARE 互斥；必须先取得此锁，再读取请求、订单和库存事实。
     * 锁持续到调用者事务结束，本方法不自行创建事务。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 受当前事务独占锁保护的控制快照
     * @throws BusinessException
     *                               控制记录不存在
     */
    private Control lock(long voucher) {
        Control control = mapper.lockControl(voucher);
        if (control == null)
            throw new BusinessException(ErrorCode.NOT_FOUND);
        return control;
    }

    /**
     * 在持有活动独占锁时，把当前 epoch 和 FROZEN 状态同步到 Redis gate。
     * <p>
     * 只覆盖这两个字段，不清空已有发布标记，也不修改库存；调用者先确认活动尚未复流。
     * 用于修复“数据库已冻结、Redis 写入未完成”的窗口。Redis 写入不随数据库事务回滚。
     *
     * @param c
     *              独占锁下读取的非 NORMAL 控制状态
     */
    private void ensureFrozen(Control c) {
        redis.opsForHash().putAll(RedisKeys.seckillGate(c.voucherId()),
                Map.of("state", "FROZEN", "epoch", String.valueOf(c.epoch())));
    }

    /**
     * 把冻结时留下的请求归一化为新代次事实，并保存可重建的额度计划。
     * <p>
     * 仅处理 FROZEN。逐条核对请求与同请求、同活动、同用户订单的对应数量：SUCCEEDED 必须有一单，
     * 其他阶段必须没有订单；冲突时中止。INTENT 若同时持有 owners 归属及旧代次、用户、活动证据，
     * 转为 ACCEPTED/RESERVED；缺少肯定证据则关闭为 CLOSED/NONE。
     * 这里允许关闭的前提是新代次冻结门禁已封住旧预扣，不能把此规则直接搬到在线对账。
     * <p>
     * 所有请求绑定到新 epoch；ACCEPTED 保留原 eventId 并重置为 PENDING 待投递，其他阶段结束其 Outbox。
     * 再检查同一用户多份受理、受理与已有订单冲突，按数据库库存计算 A/R/C。
     * 计划及 RECONCILING 状态在同一阶段事务中保存；Redis 读写本身不参与回滚。
     *
     * @param c
     *              当前事务独占锁下的控制快照；不是 FROZEN 时不操作
     * @see #currentPlan(long)
     * @see #requests(long, Consumer)
     */
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

    /**
     * 从冻结后的数据库事实重新计算 A/R/C，不采用 Redis 剩余量。
     * <p>
     * SINGLE 读取活动余量；BUCKET 只统计当前 layoutVersion，并检查桶号覆盖、桶数及桶总额度。
     * 未知模式、缺桶和查询失败都不会按零库存继续恢复。
     * C 是该活动全部已建订单数，不按付款、核销或过期状态扣减；R 是 ACCEPTED 请求数。
     * 最后由 RecoveryPlan 校验总额度与数据库余量是否一致，并计算 A = 数据库余量 - R。
     * <p>
     * 调用者应先持有活动独占锁，保证发行事实稳定；该方法本身不加锁。
     *
     * @param voucher
     *                    活动 ID
     *
     * @return 经过守恒校验的可用、在途、已发行额度
     * @see RecoveryPlan#calculate(long, long, long, long)
     */
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

    /**
     * 按已保存的计划重建 Redis，并在保持冻结的情况下发布库存、资格和归属。
     * <p>
     * 仅处理 RECONCILING；先重新计算并比较 A/R/C，变化则中止。
     * 以 recoveryId 为前缀清理并重建三份暂存结构：库存置为 A，已有订单 C 与受理请求 R 均占用用户资格和归属。
     * 请求 Hash 直接在正式键逐条写入：SUCCEEDED → SUCCESS，ACCEPTED → PROCESSING，其他阶段 → RELEASED。
     * 账本请求的 Hash 清除过期时间；putAll 覆盖给定字段，不清理未列出的旧字段。
     * <p>
     * Lua 校验冻结代次、暂存库存及成员数量，再一次替换正式库存/资格/归属，登记 publishedRecovery。
     * 这一原子范围不包含逐条请求 Hash，也不包含 MySQL。发布后仍冻结，随后数据库将关闭请求及其补偿标为已释放，
     * 并把阶段推进到 REBUILDING；此处不逐笔给 Redis 加库存。
     * <p>
     * RECOVERY_PUBLISHED 位于 Redis 发布后、数据库阶段提交前。若这里失败，数据库仍为 RECONCILING，
     * 重入会重新清空暂存并按同一计划覆盖，避免重复增加可用额度。订单与请求扫描分别受记录预算限制。
     *
     * @param c
     *              当前事务独占锁下的控制快照；整个阶段持锁，不按页提交
     * @see #resume(Control)
     * @see com.voucherx.config.RedisScriptConfig#recoveryPublishScript()
     */
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

    /**
     * 校验重建结果，先开放 Redis 门禁，再提交数据库复流及恢复任务 DONE。
     * <p>
     * 仅处理 REBUILDING。比较保存的 A/R/C 与当前数据库事实，再核对 Redis epoch、publishedRecovery、
     * 库存值及资格/归属数量；这些是数量与发布标记检查，不是逐个成员内容的完整审计。
     * 每个 ACCEPTED 请求还必须属于当前 epoch，并有 PENDING Outbox。
     * <p>
     * RECOVERY_BEFORE_RESUME 位于全部校验后、放开门禁前；暂停时仍持有活动独占锁。
     * Redis 先写 NORMAL，数据库随后写 NORMAL/DONE 和审计，两者不原子提交。
     * 数据库提交前正常发行仍受独占锁及状态约束；若数据库阶段失败，重入 recover 会先补回冻结门禁再校验。
     *
     * @param c
     *              当前事务独占锁下的控制快照；不是 REBUILDING 时不操作
     * @see #recover(long)
     */
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

    /**
     * 读取本恢复任务已落库的 A/R/C，作为重建与复流时的比较基准。
     * <p>
     * 只在计划阶段完成之后使用；比较的是额度数值，不包含所有用户和请求的身份摘要。
     *
     * @param id
     *               恢复任务 ID
     *
     * @return 保存的额度计划
     * @throws org.springframework.dao.EmptyResultDataAccessException
     *                                                                    恢复任务不存在
     */
    private RecoveryPlan savedPlan(String id) {
        var plan = mapper.savedPlan(id);
        if (plan == null)
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        return plan;
    }

    /**
     * 在调用者阶段事务内同步推进活动控制状态和恢复任务状态。
     * <p>
     * 活动更新携带 epoch 条件；阶段前置判断及独占锁由调用者负责。
     * 本方法不单独提交，也不更新 Redis gate；RECONCILING/REBUILDING 时 gate 仍为 FROZEN。
     *
     * @param c
     *                  当前独占锁下的控制快照
     * @param state
     *                  即将进入的阶段
     */
    private void transition(Control c, String state) {
        mapper.setActivityState(c.voucherId(), c.epoch(), state);
        mapper.setRunState(c.recoveryId(), state, clock.millis());
    }

    /**
     * 按 requestId 游标遍历活动的全部请求，逐条执行当前阶段的处理动作。
     * <p>
     * 使用大于上一页末尾 ID 的条件分页，不使用 offset；各阶段从头扫描，没有持久化分页进度。
     * 一次调用累计超过 maxRecords 就中止。分页控制内存用量，但不会释放阶段锁，也不产生逐页提交。
     *
     * @param voucher
     *                     活动 ID
     * @param consumer
     *                     计划归一化或 Redis 请求重建动作；异常直接交给阶段事务处理
     * @see RecoveryProperties
     */
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

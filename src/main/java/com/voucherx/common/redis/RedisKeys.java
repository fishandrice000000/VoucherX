package com.voucherx.common.redis;

/**
 * 集中定义 Redis 键名，只拼接字符串，不读写 Redis。
 * <p>
 * 01 库存链路按 stock、users、request、owners、gate 五个键调用预留脚本。
 * stock 是活动总额度，users 表示谁占了资格，owners 进一步记录资格归属于哪个 requestId；
 * BUCKET 模式也不额外生成每只 MySQL 桶的 Redis 库存键。
 * <p>
 * 当前库存脚本的这五个键没有共同的 Redis Cluster hash tag，不能据此认为已支持集群多键执行。
 *
 * @see com.voucherx.seckill.service.SeckillStockService
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String authCode(String phone) {
        return "voucherx:auth:code:" + phone;
    }

    public static String authCodeCooldown(String phone) {
        return "voucherx:auth:code:cooldown:" + phone;
    }

    public static String authCodeIpLimit(String ip) {
        return "voucherx:auth:code:ip:" + ip;
    }

    public static String token(String token) {
        return "voucherx:auth:token:" + token;
    }

    public static String shopCache(Long shopId) {
        return "voucherx:cache:shop:" + shopId;
    }

    public static String shopLock(Long shopId) {
        return "voucherx:lock:shop:" + shopId;
    }

    /**
     * 预留脚本 KEYS[1]：活动级总额度字符串；Lua 成功新预留时 DECR，幂等释放时 INCR。
     *
     * @param voucherId
     *                      活动 ID
     * @return 形如 voucherx:seckill:stock:2001 的键
     */
    public static String seckillStock(Long voucherId) {
        return "voucherx:seckill:stock:" + voucherId;
    }

    /**
     * 预留脚本 KEYS[2]：已占领取资格的用户 Set；集合成员是 userId，不是剩余库存。
     *
     * @param voucherId
     *                      活动 ID
     * @return 该活动的用户资格集合键
     */
    public static String seckillUsers(Long voucherId) {
        return "voucherx:seckill:users:" + voucherId;
    }

    /**
     * 预留脚本 KEYS[4]：用户到请求的归属 Hash，字段 userId、值 requestId；同请求重放与释放核验依赖它。
     *
     * @param id
     *               活动 ID
     * @return 该活动的预留归属 Hash 键
     */
    public static String seckillOwners(Long id) {
        return "voucherx:seckill:owners:" + id;
    }

    /**
     * 预留脚本 KEYS[5]：活动恢复门禁 Hash，保存 state 和 epoch；由初始化器显式写入；缺失时脚本拒绝执行。
     *
     * @param id
     *               活动 ID
     * @return 该活动的门禁 Hash 键
     */
    public static String seckillGate(Long id) {
        return "voucherx:seckill:gate:" + id;
    }

    /**
     * 预留脚本 KEYS[3]：单次请求 Hash，保存身份、布局、代次及状态；活跃预留不靠 TTL 到期判失败。
     *
     * @param requestId
     *                      抢券请求 ID
     * @return 形如 voucherx:seckill:request:R1 的键
     */
    public static String seckillRequest(String requestId) {
        return "voucherx:seckill:request:" + requestId;
    }

    /**
     * Redis 双桶获取脚本的 KEYS[1]，以及策略更新脚本的 KEYS[1]：保存共享余额 tokens 与结算 Unix 毫秒 ts。
     * <p>
     * 02 四个准入键使用同一个 admission hash tag；当前项目仍只支持并验证单节点 Redis，不能据此推断库存已完成集群分片。
     *
     * @return voucherx:{admission}:shared:tokens
     */
    public static String admissionSharedTokens() {
        return "voucherx:{admission}:shared:tokens";
    }

    /**
     * 获取脚本 KEYS[2] 及策略更新脚本 KEYS[2]：保存共享 rate、capacity、validUntil 和更新时间。
     *
     * @return voucherx:{admission}:shared:policy
     */
    public static String admissionSharedPolicy() {
        return "voucherx:{admission}:shared:policy";
    }

    /**
     * 获取脚本 KEYS[3]：保存单活动的 tokens 和 ts，与共享桶在同一次 Lua 中结算和消费。
     *
     * @param voucherId
     *                      当前活动 ID
     * @return 该活动的准入令牌 Hash 键
     */
    public static String admissionActivityTokens(Long voucherId) {
        return "voucherx:{admission}:activity:" + voucherId + ":tokens";
    }

    /**
     * 获取脚本 KEYS[4]：保存单活动的固定 rate/capacity 与有效截止，不由共享 AIMD 控制器逐活动调度。
     *
     * @param voucherId
     *                      当前活动 ID
     * @return 该活动的准入策略 Hash 键
     */
    public static String admissionActivityPolicy(Long voucherId) {
        return "voucherx:{admission}:activity:" + voucherId + ":policy";
    }
}

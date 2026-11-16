package com.voucherx.common.redis;

/**
 * 生成各业务 Redis 结构的键名。
 * <p>
 * 预留脚本按 stock、users、request、owners、gate 传入五个键；两种 MySQL 库存模式共用一个活动总库存键。
 * users 记录已占资格的用户，owners 记录用户对应的 requestId，请求 Hash 保存身份、布局和过程状态。
 * 库存多键 Lua 使用单节点 Redis 执行。
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
     * 预留脚本 KEYS[2]，以用户 ID 为成员记录已占领取资格。
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
     * 共享令牌 Hash 键，保存余额 tokens 和结算时刻 ts，供准入获取和策略更新脚本使用。
     * <p>
     * 四个准入键具有共同的 admission hash tag；当前运行环境使用单节点 Redis。
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

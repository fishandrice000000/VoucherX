package com.voucherx.common.redis;

/**
 * 统一 Redis Key 命名规范：voucherx:{领域}:{对象}:{标识}。
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

    public static String seckillStock(Long voucherId) {
        return "voucherx:seckill:stock:" + voucherId;
    }

    public static String seckillUsers(Long voucherId) {
        return "voucherx:seckill:users:" + voucherId;
    }

    public static String seckillOwners(Long id) { return "voucherx:seckill:owners:" + id; }
    public static String seckillGate(Long id) { return "voucherx:seckill:gate:" + id; }

    public static String seckillRequest(String requestId) {
        return "voucherx:seckill:request:" + requestId;
    }

    /**
     * 方向 02 准入 Key 使用同一 hash tag，为未来 Redis Cluster 同槽操作保留空间；
     * 当前只支持并验证单节点 Redis。
     */
    public static String admissionSharedTokens() {
        return "voucherx:{admission}:shared:tokens";
    }

    public static String admissionSharedPolicy() {
        return "voucherx:{admission}:shared:policy";
    }

    public static String admissionActivityTokens(Long voucherId) {
        return "voucherx:{admission}:activity:" + voucherId + ":tokens";
    }

    public static String admissionActivityPolicy(Long voucherId) {
        return "voucherx:{admission}:activity:" + voucherId + ":policy";
    }
}

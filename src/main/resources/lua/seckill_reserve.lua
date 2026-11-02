-- 秒杀 Redis 预扣：库存检查、一人一单检查、扣库存、占资格、写请求状态必须原子完成。
-- KEYS[1] 库存 Key
-- KEYS[2] 已参与用户 Set
-- KEYS[3] 请求状态 Hash
-- ARGV[1] userId
-- ARGV[2] requestId
-- ARGV[3] 当前毫秒时间戳
-- ARGV[4] 活动开始毫秒时间戳
-- ARGV[5] 活动结束毫秒时间戳
-- ARGV[6] voucherId
-- ARGV[7] 库存模式（SINGLE / BUCKET）
-- ARGV[8] 起始 MySQL 桶编号
-- ARGV[9] 布局版本
-- 返回 >= 0 表示剩余库存；-1 库存未初始化；-2 未开始；-3 已结束；-4 重复参与；-5 库存不足。
local stockKey = KEYS[1]
local usersKey = KEYS[2]
local requestKey = KEYS[3]
local userId = ARGV[1]
local requestId = ARGV[2]
local now = tonumber(ARGV[3])
local beginAt = tonumber(ARGV[4])
local endAt = tonumber(ARGV[5])
local voucherId = ARGV[6]
local stockMode = ARGV[7]
local startBucketNo = ARGV[8]
local layoutVersion = ARGV[9]

-- 活跃预留保留到明确终态/受控重建，不能以 TTL 作为恢复依据。
local ownersKey = KEYS[4]
local gateKey = KEYS[5]
local epoch = ARGV[10] or '1'
local gateEpoch = redis.call('HGET', gateKey, 'epoch') or '1'
local gateState = redis.call('HGET', gateKey, 'state') or 'NORMAL'
if gateEpoch ~= epoch or gateState ~= 'NORMAL' then return -6 end
if redis.call('HGET', ownersKey, userId) == requestId then
    local remaining = tonumber(redis.call('GET', stockKey))
    if remaining == nil then return -1 end
    return remaining
end
if redis.call('EXISTS', stockKey) == 0 then
    return -1
end
if now < beginAt then
    return -2
end
if now > endAt then
    return -3
end
if redis.call('SISMEMBER', usersKey, userId) == 1 then
    return -4
end
local stock = tonumber(redis.call('GET', stockKey))
if stock == nil then
    return -1
end
if stock <= 0 then
    return -5
end

redis.call('DECR', stockKey)
redis.call('SADD', usersKey, userId)
redis.call('HSET', ownersKey, userId, requestId)
redis.call('HSET', requestKey,
        'requestId', requestId,
        'voucherId', voucherId,
        'userId', userId,
        'status', 'PENDING',
        'stockMode', stockMode,
        'startBucketNo', startBucketNo,
        'layoutVersion', layoutVersion,
        'epoch', epoch, 'createdAt', now)
redis.call('PERSIST', requestKey)
return stock - 1

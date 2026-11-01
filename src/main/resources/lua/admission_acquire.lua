-- 秒杀全局准入：共享桶与活动桶先判断、再同时扣减，避免部分扣减。
-- KEYS[1] 共享令牌 Hash: tokens, ts
-- KEYS[2] 共享策略 Hash: rate, capacity, validUntil
-- KEYS[3] 活动令牌 Hash: tokens, ts
-- KEYS[4] 活动策略 Hash: rate, capacity, validUntil
-- ARGV[1] 本次成本
-- ARGV[2] 默认共享速率
-- ARGV[3] 默认共享容量
-- ARGV[4] 默认活动速率
-- ARGV[5] 默认活动容量
-- 返回 1 放行；-1 共享桶不足；-2 活动桶不足；-3 策略过期；-4 非法策略。
local function nowMillis()
    local t = redis.call('TIME')
    return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local function validNumber(value, allowZero)
    if value == nil or value ~= value then
        return false
    end
    if value < 0 then
        return false
    end
    if not allowZero and value <= 0 then
        return false
    end
    return true
end

local function readPolicy(key, defaultRate, defaultCapacity)
    local rate = tonumber(redis.call('HGET', key, 'rate'))
    local capacity = tonumber(redis.call('HGET', key, 'capacity'))
    local validUntil = tonumber(redis.call('HGET', key, 'validUntil') or '0')
    if rate == nil or capacity == nil then
        rate = defaultRate
        capacity = defaultCapacity
        redis.call('HSET', key, 'rate', tostring(rate), 'capacity', tostring(capacity),
                'validUntil', '0', 'updatedAt', tostring(nowMillis()))
        redis.call('EXPIRE', key, 604800)
    end
    if not validNumber(rate, true) or not validNumber(capacity, false) then
        return nil, nil, 'INVALID'
    end
    if validUntil ~= nil and validUntil > 0 and nowMillis() > validUntil then
        return rate, capacity, 'STALE'
    end
    return rate, capacity, nil
end

local function settle(tokenKey, rate, capacity, now)
    local tokens = tonumber(redis.call('HGET', tokenKey, 'tokens'))
    local ts = tonumber(redis.call('HGET', tokenKey, 'ts'))
    if tokens == nil or ts == nil then
        tokens = capacity
        ts = now
        redis.call('EXPIRE', tokenKey, 86400)
    end
    if now < ts then
        -- 服务端时间回退时，不把负经过时间用于补充。
        ts = now
    end
    if rate <= 0 then
        tokens = 0
    else
        tokens = math.min(capacity, tokens + (now - ts) / 1000 * rate)
    end
    return tokens, ts
end

local now = nowMillis()
local cost = tonumber(ARGV[1])
if not validNumber(cost, false) then
    return -4
end

local sharedRate, sharedCapacity, sharedError = readPolicy(KEYS[2],
        tonumber(ARGV[2]), tonumber(ARGV[3]))
if sharedError ~= nil then
    if sharedError == 'STALE' then
        return -3
    end
    return -4
end
local activityRate, activityCapacity, activityError = readPolicy(KEYS[4],
        tonumber(ARGV[4]), tonumber(ARGV[5]))
if activityError ~= nil then
    if activityError == 'STALE' then
        return -3
    end
    return -4
end

local sharedTokens, sharedTs = settle(KEYS[1], sharedRate, sharedCapacity, now)
local activityTokens, activityTs = settle(KEYS[3], activityRate, activityCapacity, now)

if sharedTokens + 1e-9 < cost then
    redis.call('HSET', KEYS[1], 'tokens', tostring(sharedTokens), 'ts', tostring(sharedTs))
    redis.call('HSET', KEYS[3], 'tokens', tostring(activityTokens), 'ts', tostring(activityTs))
    return -1
end
if activityTokens + 1e-9 < cost then
    redis.call('HSET', KEYS[1], 'tokens', tostring(sharedTokens), 'ts', tostring(sharedTs))
    redis.call('HSET', KEYS[3], 'tokens', tostring(activityTokens), 'ts', tostring(activityTs))
    return -2
end

redis.call('HSET', KEYS[1], 'tokens', tostring(sharedTokens - cost), 'ts', tostring(now))
redis.call('HSET', KEYS[3], 'tokens', tostring(activityTokens - cost), 'ts', tostring(now))
return 1

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
-- ARGV[6] 模式：FIXED / ADAPTIVE（必须显式传入）
-- 返回 1 放行；-1 共享桶不足；-2 活动桶不足；-3 策略缺失/过期；-4 非法策略。
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

local function readPolicy(key, defaultRate, defaultCapacity, requireValidPolicy)
    local rate = tonumber(redis.call('HGET', key, 'rate'))
    local capacity = tonumber(redis.call('HGET', key, 'capacity'))
    if rate == nil or capacity == nil then
        if requireValidPolicy then
            -- 自适应策略只能由控制器发布；缺失时不能退回永久有效的默认值。
            return nil, nil, 'MISSING'
        end
        rate = defaultRate
        capacity = defaultCapacity
        redis.call('HSET', key, 'rate', tostring(rate), 'capacity', tostring(capacity),
                'validUntil', '0', 'updatedAt', tostring(nowMillis()))
        redis.call('EXPIRE', key, 604800)
    end
    local validUntil = tonumber(redis.call('HGET', key, 'validUntil') or '0')
    if not validNumber(rate, true) or not validNumber(capacity, false) then
        return nil, nil, 'INVALID'
    end
    if requireValidPolicy and (validUntil == nil or validUntil <= 0) then
        -- 自适应策略必须有有限有效期，旧的永久策略不能继续使用。
        return nil, nil, 'MISSING'
    end
    if validUntil ~= nil and validUntil > 0 and nowMillis() > validUntil then
        return rate, capacity, 'STALE'
    end
    if redis.call('TTL', key) < 0 then
        redis.call('EXPIRE', key, 604800)
    end
    return rate, capacity, nil
end

local function settle(tokenKey, rate, capacity, now)
    local tokens = tonumber(redis.call('HGET', tokenKey, 'tokens'))
    local ts = tonumber(redis.call('HGET', tokenKey, 'ts'))
    if tokens == nil or ts == nil then
        tokens = capacity
        ts = now
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
    -- 返回结算后的时间戳；拒绝分支也必须推进 ts，否则下一次会重复补充同一段时间。
    return tokens, now
end

local function ensureTokenTtl(key)
    -- 必须在 HSET 创建 Key 之后调用；对已存在的 Key 只在没有 TTL 时补一次。
    if redis.call('TTL', key) < 0 then
        redis.call('EXPIRE', key, 86400)
    end
end

local now = nowMillis()
local cost = tonumber(ARGV[1])
if not validNumber(cost, false) then
    return -4
end

local mode = ARGV[6]
if mode ~= 'FIXED' and mode ~= 'ADAPTIVE' then return -4 end
local requireSharedPolicy = mode == 'ADAPTIVE'
local sharedRate, sharedCapacity, sharedError = readPolicy(KEYS[2],
        tonumber(ARGV[2]), tonumber(ARGV[3]), requireSharedPolicy)
if sharedError ~= nil then
    if sharedError == 'STALE' or sharedError == 'MISSING' then
        return -3
    end
    return -4
end
-- 活动桶是静态配额，即使自适应共享策略要求控制器发布，活动策略仍可按配置初始化。
local activityRate, activityCapacity, activityError = readPolicy(KEYS[4],
        tonumber(ARGV[4]), tonumber(ARGV[5]), false)
if activityError ~= nil then
    if activityError == 'STALE' or activityError == 'MISSING' then
        return -3
    end
    return -4
end

local sharedTokens, sharedTs = settle(KEYS[1], sharedRate, sharedCapacity, now)
local activityTokens, activityTs = settle(KEYS[3], activityRate, activityCapacity, now)

if sharedTokens + 1e-9 < cost then
    redis.call('HSET', KEYS[1], 'tokens', tostring(sharedTokens), 'ts', tostring(sharedTs))
    redis.call('HSET', KEYS[3], 'tokens', tostring(activityTokens), 'ts', tostring(activityTs))
    ensureTokenTtl(KEYS[1])
    ensureTokenTtl(KEYS[3])
    return -1
end
if activityTokens + 1e-9 < cost then
    redis.call('HSET', KEYS[1], 'tokens', tostring(sharedTokens), 'ts', tostring(sharedTs))
    redis.call('HSET', KEYS[3], 'tokens', tostring(activityTokens), 'ts', tostring(activityTs))
    ensureTokenTtl(KEYS[1])
    ensureTokenTtl(KEYS[3])
    return -2
end

redis.call('HSET', KEYS[1], 'tokens', tostring(sharedTokens - cost), 'ts', tostring(now))
redis.call('HSET', KEYS[3], 'tokens', tostring(activityTokens - cost), 'ts', tostring(now))
ensureTokenTtl(KEYS[1])
ensureTokenTtl(KEYS[3])
return 1

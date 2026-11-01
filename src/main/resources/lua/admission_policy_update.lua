-- 更新共享准入策略。先按旧速率结算到当前时刻，再写入新速率/容量。
-- KEYS[1] 令牌 Hash
-- KEYS[2] 策略 Hash
-- ARGV[1] 新速率（请求/秒）
-- ARGV[2] 新容量
-- ARGV[3] validUntil 绝对毫秒时间戳；0 表示长期有效
-- 返回 1 成功；-1 参数非法。
local function nowMillis()
    local t = redis.call('TIME')
    return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local newRate = tonumber(ARGV[1])
local newCapacity = tonumber(ARGV[2])
local validUntil = tonumber(ARGV[3])
if newRate == nil or newRate ~= newRate or newRate < 0
        or newCapacity == nil or newCapacity ~= newCapacity or newCapacity <= 0
        or validUntil == nil or validUntil < 0 then
    return -1
end

local now = nowMillis()
local oldRate = tonumber(redis.call('HGET', KEYS[2], 'rate'))
local oldCapacity = tonumber(redis.call('HGET', KEYS[2], 'capacity'))
local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
local ts = tonumber(redis.call('HGET', KEYS[1], 'ts'))
if oldRate == nil or oldCapacity == nil then
    oldRate = newRate
    oldCapacity = newCapacity
end
if tokens == nil or ts == nil then
    tokens = newCapacity
    ts = now
end
if now < ts then
    ts = now
end
if oldRate <= 0 then
    tokens = 0
else
    tokens = math.min(oldCapacity, tokens + (now - ts) / 1000 * oldRate)
end
tokens = math.min(tokens, newCapacity)
if newRate <= 0 then
    tokens = 0
end

redis.call('HSET', KEYS[2],
        'rate', tostring(newRate),
        'capacity', tostring(newCapacity),
        'validUntil', tostring(validUntil),
        'updatedAt', tostring(now))
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('EXPIRE', KEYS[1], 86400)
redis.call('EXPIRE', KEYS[2], 604800)
return 1

-- 幂等释放 Redis 预扣。只允许释放尚未成功、尚未释放的请求。
-- KEYS[1] 库存 Key
-- KEYS[2] 已参与用户 Set
-- KEYS[3] 请求状态 Hash
-- ARGV[1] userId
-- ARGV[2] 释放原因
-- 返回 1 表示本次执行了释放，0 表示无需释放（重复释放或状态不允许）。
local stockKey = KEYS[1]
local usersKey = KEYS[2]
local requestKey = KEYS[3]
local userId = ARGV[1]
local reason = ARGV[2]

if redis.call('EXISTS', requestKey) == 0 then
    return 0
end
local status = redis.call('HGET', requestKey, 'status')
if status == 'RELEASED' or status == 'SUCCESS' then
    return 0
end
if redis.call('SISMEMBER', usersKey, userId) == 0 then
    return 0
end

redis.call('SREM', usersKey, userId)
redis.call('INCR', stockKey)
redis.call('HSET', requestKey, 'status', 'RELEASED', 'reason', reason)
redis.call('EXPIRE', requestKey, 86400)
return 1

-- 原子校验并删除验证码，避免同一个验证码被重复使用。
-- KEYS[1] 验证码 Key；ARGV[1] 用户提交的验证码。
-- 返回 1 成功，0 不匹配，-1 已过期/不存在。
local exists = redis.call('EXISTS', KEYS[1])
if exists == 0 then
    return -1
end
local expected = redis.call('GET', KEYS[1])
if expected ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1])
return 1

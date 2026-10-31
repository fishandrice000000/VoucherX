-- 只释放自己持有的分布式锁，避免误删其他请求重建缓存时持有的锁。
-- KEYS[1] 锁 Key；ARGV[1] 本次请求生成的锁值。
local current = redis.call('GET', KEYS[1])
if current == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0

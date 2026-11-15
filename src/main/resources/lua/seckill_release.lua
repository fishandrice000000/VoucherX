-- 调用前必须在 MySQL 关闭请求，阻止同请求迟到成功。1=释放，2=已释放，负数=需隔离。
local stockKey, usersKey, requestKey, ownersKey, gateKey = KEYS[1],KEYS[2],KEYS[3],KEYS[4],KEYS[5]
local userId, reason, requestId, epoch = ARGV[1],ARGV[2],ARGV[3],ARGV[4]
if #KEYS ~= 5 or #ARGV ~= 4 or tonumber(epoch) == nil or tonumber(epoch) < 1 then return -2 end
if redis.call('HGET',gateKey,'epoch') ~= epoch then return -2 end
if redis.call('HGET',gateKey,'state') ~= 'NORMAL' then return -2 end
if redis.call('EXISTS',stockKey)==0 or redis.call('EXISTS',requestKey)==0 then return -3 end
if redis.call('HGET',requestKey,'requestId')~=requestId or redis.call('HGET',requestKey,'userId')~=userId
    or redis.call('HGET',requestKey,'epoch')~=epoch then return -3 end
local status=redis.call('HGET',requestKey,'status')
if status=='RELEASED' then return 2 end
if status=='SUCCESS' then return -4 end
if redis.call('HGET',ownersKey,userId)~=requestId then return -5 end
local stock=tonumber(redis.call('GET',stockKey))
if stock==nil or stock<0 then return -3 end
redis.call('HDEL',ownersKey,userId)
redis.call('SREM',usersKey,userId)
redis.call('INCR',stockKey)
redis.call('HSET',requestKey,'status','RELEASED','reason',reason)
redis.call('PERSIST',requestKey)
return 1

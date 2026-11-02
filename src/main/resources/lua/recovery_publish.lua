-- 库存/资格/归属整组发布，期间保持 gate FROZEN。只支持当前单节点 Redis。
-- KEYS: liveStock,liveUsers,liveOwners,gate,stageStock,stageUsers,stageOwners
-- ARGV: epoch,recoveryId,available,members
if redis.call('HGET',KEYS[4],'epoch')~=ARGV[1] or redis.call('HGET',KEYS[4],'state')~='FROZEN' then return -1 end
if redis.call('GET',KEYS[5])~=ARGV[3] then return -2 end
local members=tonumber(ARGV[4])
if redis.call('SCARD',KEYS[6])~=members or redis.call('HLEN',KEYS[7])~=members then return -3 end
redis.call('RENAME',KEYS[5],KEYS[1])
redis.call('DEL',KEYS[2],KEYS[3])
if members>0 then
    redis.call('RENAME',KEYS[6],KEYS[2])
    redis.call('RENAME',KEYS[7],KEYS[3])
end
redis.call('HSET',KEYS[4],'publishedRecovery',ARGV[2],'available',ARGV[3],'members',ARGV[4])
return 1

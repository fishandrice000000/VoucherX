-- 数据库结果已确定后，只更新 SUCCESS/FAILED；保护已成功和已释放状态。
-- KEYS[1] 请求状态 Hash
-- ARGV[1] 目标状态
-- ARGV[2] eventId，空字符串表示不更新
-- ARGV[3] orderId，空字符串表示不更新
-- ARGV[4] reason，空字符串表示不更新
-- 返回 1 表示执行了更新，0 表示状态不存在或被终态保护拒绝。
local key = KEYS[1]
local newStatus = ARGV[1]
local current = redis.call('HGET', key, 'status')
if not current then
    return 0
end

local function allowed()
    if newStatus == 'SUCCESS' then
        -- 数据库已提交订单是最高优先级终态，可纠正迟到的失败/未知状态。
        return current ~= 'SUCCESS'
    end
    if current == 'SUCCESS' then
        return false
    end
    if newStatus == 'FAILED' then
        return current ~= 'RELEASED'
    end
    if current == 'RELEASED' then
        return false
    end
    return false
end

if not allowed() then
    return 0
end

redis.call('HSET', key, 'status', newStatus)
if ARGV[2] ~= '' then
    redis.call('HSET', key, 'eventId', ARGV[2])
end
if ARGV[3] ~= '' then
    redis.call('HSET', key, 'orderId', ARGV[3])
end
if ARGV[4] ~= '' then
    redis.call('HSET', key, 'reason', ARGV[4])
end
return 1

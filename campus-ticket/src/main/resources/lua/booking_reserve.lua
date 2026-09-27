-- KEYS[1]：库存Hash
-- KEYS[2]：申请记录Hash
-- KEYS[3]：待投递ZSet
--
-- ARGV[1]：库存批次epoch
-- ARGV[2]：用户ID
-- ARGV[3]：订单ID
-- ARGV[4]：活动ID
-- ARGV[5]：申请失效时间，毫秒时间戳

-- 写入前检查key类型，避免后续命令出现WRONGTYPE
local inventoryType = redis.call('TYPE', KEYS[1]).ok
local requestsType = redis.call('TYPE', KEYS[2]).ok
local pendingType = redis.call('TYPE', KEYS[3]).ok

if inventoryType ~= 'hash' then
    return {'UNAVAILABLE'}
end

if requestsType ~= 'none' and requestsType ~= 'hash' then
    return {'INVALID_DATA'}
end

if pendingType ~= 'none' and pendingType ~= 'zset' then
    return {'INVALID_DATA'}
end

if redis.call('HGET', KEYS[1], 'ready') ~= '1' then
    return {'UNAVAILABLE'}
end

if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1] then
    return {'EPOCH_CHANGED'}
end

-- 终态标记防止已经取消的旧请求再次预占
if redis.call('HEXISTS', KEYS[1], 'x:' .. ARGV[3]) == 1 then
    return {'TERMINAL'}
end

-- 重复请求不能再次扣名额
local owner = redis.call('HGET', KEYS[1], 'u:' .. ARGV[2])

if owner then
    if owner == ARGV[3] then
        if redis.call('HEXISTS', KEYS[2], ARGV[3]) == 1 then
            return {'OK', owner}
        end

        return {'INVALID_DATA'}
    end

    return {'DUPLICATE', owner}
end

-- 防止订单ID被另一个申请重复使用
if redis.call('HEXISTS', KEYS[2], ARGV[3]) == 1 then
    return {'ORDER_ID_CONFLICT'}
end

local startTime = tonumber(redis.call('HGET', KEYS[1], 'start'))
local endTime = tonumber(redis.call('HGET', KEYS[1], 'end'))
local quota = tonumber(redis.call('HGET', KEYS[1], 'quota'))
local expiresAt = tonumber(ARGV[5])

if not startTime or not endTime or not quota or not expiresAt then
    return {'INVALID_DATA'}
end

if quota < 0 or quota ~= math.floor(quota) then
    return {'INVALID_DATA'}
end

-- 使用Redis服务器时间，避免不同应用实例使用不同时间判断
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)

if now < startTime then
    return {'NOT_STARTED'}
end

if now >= endTime then
    return {'CLOSED'}
end

if now >= expiresAt then
    return {'EXPIRED'}
end

if quota == 0 then
    return {'SOLD_OUT'}
end

-- 先构造JSON，再执行写入
-- ID保留字符串，避免Lua数值精度影响大整数ID
local requestJson = cjson.encode({
    orderId = ARGV[3],
    userId = ARGV[2],
    activityId = ARGV[4],
    epoch = ARGV[1],
    status = 'PENDING',
    acceptedAtMillis = now,
    expiresAtMillis = expiresAt
})

-- 原子完成预占、记录申请、加入待投递列表
redis.call('HINCRBY', KEYS[1], 'quota', -1)
redis.call('HSET', KEYS[1], 'u:' .. ARGV[2], ARGV[3])
redis.call('HSET', KEYS[2], ARGV[3], requestJson)
redis.call('ZADD', KEYS[3], now, ARGV[3])

return {'OK', ARGV[3]}
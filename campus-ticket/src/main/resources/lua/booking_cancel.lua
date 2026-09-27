-- KEYS[1]：库存Hash
-- KEYS[2]：申请记录Hash
-- KEYS[3]：待投递ZSet
--
-- ARGV[1]：订单编号
-- ARGV[2]：用户ID
-- ARGV[3]：活动ID
-- ARGV[4]：批次
-- ARGV[5]：报名记录ID

local inventoryType = redis.call('TYPE', KEYS[1]).ok
local requestsType = redis.call('TYPE', KEYS[2]).ok
local pendingType = redis.call('TYPE', KEYS[3]).ok

if inventoryType ~= 'hash' or requestsType ~= 'hash' then
	return 0
end

if pendingType ~= 'none' and pendingType ~= 'zset' then
	return -1
end

if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[4] then
	return -1
end

local requestJson = redis.call('HGET', KEYS[2], ARGV[1])

if not requestJson then
	return 0
end

local decoded, request = pcall(cjson.decode, requestJson)

if not decoded or type(request) ~= 'table' then
	return -1
end

if request.orderId ~= ARGV[1]
or request.userId ~= ARGV[2]
or request.activityId ~= ARGV[3]
or request.epoch ~= ARGV[4] then
	return -1
end

if request.registrationId and request.registrationId ~= ARGV[5] then
	return -1
end

local terminalField = 'x:' .. ARGV[1]
local ownerField = 'u:' .. ARGV[2]

-- 已经取消：不再归还名额，也不能删除后来新订单的用户占用标记
if request.status == 'CANCELLED' then
	if request.registrationId ~= ARGV[5]
	or redis.call('HGET', KEYS[1], terminalField) ~= '1' then
		return -1
	end

	redis.call('ZREM', KEYS[3], ARGV[1])
	return 1
end

-- 数据库可能已成功，但Redis尚未收到成功回写，所以也允许PENDING
if request.status ~= 'SUCCEEDED' and request.status ~= 'PENDING' then
	return -1
end

if redis.call('HGET', KEYS[1], ownerField) ~= ARGV[1] then
	return -1
end

if redis.call('HEXISTS', KEYS[1], terminalField) == 1 then
	return -1
end

local quota = tonumber(redis.call('HGET', KEYS[1], 'quota'))

if not quota or quota < 0 or quota ~= math.floor(quota) then
	return -1
end

request.status = 'CANCELLED'
request.registrationId = ARGV[5]

local updatedJson = cjson.encode(request)

redis.call('HINCRBY', KEYS[1], 'quota', 1)
redis.call('HDEL', KEYS[1], ownerField)
redis.call('HSET', KEYS[1], terminalField, '1')
redis.call('HSET', KEYS[2], ARGV[1], updatedJson)
redis.call('ZREM', KEYS[3], ARGV[1])

return 1
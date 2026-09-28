-- KEYS[1]：活动库存 Hash
-- KEYS[2]：报名申请 Hash
-- KEYS[3]：待投递 ZSet
-- KEYS[4]：本次释放名额的流转 Hash
--
-- ARGV[1]：任务 ID
-- ARGV[2]：名额流转 ID
-- ARGV[3]：原订单 ID
-- ARGV[4]：原用户 ID
-- ARGV[5]：活动 ID
-- ARGV[6]：库存批次
-- ARGV[7]：报名记录 ID

local inventoryType = redis.call('TYPE', KEYS[1]).ok
local requestsType = redis.call('TYPE', KEYS[2]).ok
local pendingType = redis.call('TYPE', KEYS[3]).ok
local holdType = redis.call('TYPE', KEYS[4]).ok

if inventoryType ~= 'hash' or requestsType ~= 'hash' then
	return 'NOT_READY'
end

if pendingType ~= 'none' and pendingType ~= 'zset' then
	return 'INVALID_DATA'
end

if holdType ~= 'none' and holdType ~= 'hash' then
	return 'INVALID_DATA'
end

if redis.call('HGET', KEYS[1], 'ready') ~= '1' then
	return 'NOT_READY'
end

if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[6] then
	return 'EPOCH_CHANGED'
end

-- 同一任务已经完成过接管：仅确认结果，不再次修改任何数据。
-- 即使名额后来已经转交其他邀请，也不能重新改回 HELD。
if holdType == 'hash' then
	if redis.call('HGET', KEYS[4], 'holdTaskId') == ARGV[1]
	and redis.call('HGET', KEYS[4], 'quotaId') == ARGV[2]
	and redis.call('HGET', KEYS[4], 'sourceOrderId') == ARGV[3]
	and redis.call('HGET', KEYS[4], 'sourceUserId') == ARGV[4]
	and redis.call('HGET', KEYS[4], 'activityId') == ARGV[5]
	and redis.call('HGET', KEYS[4], 'epoch') == ARGV[6]
	and redis.call('HGET', KEYS[4], 'registrationId') == ARGV[7] then
		return 'ALREADY_APPLIED'
	end

	return 'HOLD_CONFLICT'
end

local requestJson = redis.call('HGET', KEYS[2], ARGV[3])

if not requestJson then
	return 'REQUEST_MISSING'
end

local decoded, request = pcall(cjson.decode, requestJson)

if not decoded or type(request) ~= 'table' then
	return 'INVALID_DATA'
end

if request.orderId ~= ARGV[3]
or request.userId ~= ARGV[4]
or request.activityId ~= ARGV[5]
or request.epoch ~= ARGV[6] then
	return 'REQUEST_MISMATCH'
end

if request.registrationId and request.registrationId ~= ARGV[7] then
	return 'REQUEST_MISMATCH'
end

-- 数据库可能已经成功，而 Redis 成功回写尚未完成。
if request.status ~= 'PENDING' and request.status ~= 'SUCCEEDED' then
	return 'STATE_CONFLICT'
end

local ownerField = 'u:' .. ARGV[4]
local terminalField = 'x:' .. ARGV[3]

if redis.call('HGET', KEYS[1], ownerField) ~= ARGV[3] then
	return 'OWNER_CONFLICT'
end

if redis.call('HEXISTS', KEYS[1], terminalField) == 1 then
	return 'STATE_CONFLICT'
end

local publicQuota = tonumber(redis.call('HGET', KEYS[1], 'quota'))

if not publicQuota or publicQuota < 0 or publicQuota ~= math.floor(publicQuota) then
	return 'INVALID_DATA'
end

-- 先完成校验和 JSON 编码，再开始写入。
request.status = 'CANCELLED'
request.registrationId = ARGV[7]
request.waitlistQuotaId = ARGV[2]

local updatedJson = cjson.encode(request)

redis.call('HSET', KEYS[4],
	'holdTaskId', ARGV[1],
	'quotaId', ARGV[2],
	'sourceOrderId', ARGV[3],
	'sourceUserId', ARGV[4],
	'activityId', ARGV[5],
	'epoch', ARGV[6],
	'registrationId', ARGV[7],
	'state', 'HELD',
	'version', '0'
)

redis.call('HDEL', KEYS[1], ownerField)
redis.call('HSET', KEYS[1], terminalField, '1')
redis.call('HSET', KEYS[2], ARGV[3], updatedJson)
redis.call('ZREM', KEYS[3], ARGV[3])

-- 注意：这里没有增加公开库存。
return 'APPLIED'
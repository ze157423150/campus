-- KEYS[1]：活动库存 Hash
-- KEYS[2]：名额流转 Hash
--
-- ARGV[1]：任务 ID
-- ARGV[2]：名额流转 ID
-- ARGV[3]：活动 ID
-- ARGV[4]：原订单 ID
-- ARGV[5]：库存批次
-- ARGV[6]：期望的旧版本
-- ARGV[7]：本次任务的新版本

local inventoryType = redis.call('TYPE', KEYS[1]).ok
local quotaType = redis.call('TYPE', KEYS[2]).ok

if inventoryType ~= 'hash' or quotaType ~= 'hash' then
	return 'NOT_READY'
end

if redis.call('HGET', KEYS[1], 'ready') ~= '1' then
	return 'NOT_READY'
end

if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[5] then
	return 'EPOCH_CHANGED'
end

if redis.call('HGET', KEYS[2], 'quotaId') ~= ARGV[2]
or redis.call('HGET', KEYS[2], 'activityId') ~= ARGV[3]
or redis.call('HGET', KEYS[2], 'sourceOrderId') ~= ARGV[4]
or redis.call('HGET', KEYS[2], 'epoch') ~= ARGV[5] then
	return 'QUOTA_MISMATCH'
end

local state = redis.call('HGET', KEYS[2], 'state')
local version = redis.call('HGET', KEYS[2], 'version')

-- 相同任务已经归还过，不允许再次增加库存。
if state == 'RETURNED' then
	if redis.call('HGET', KEYS[2], 'returnTaskId') == ARGV[1]
	and version == ARGV[7] then
		return 'ALREADY_APPLIED'
	end

	return 'RETURN_CONFLICT'
end

if state ~= 'HELD' then
	return 'STATE_CONFLICT'
end

if version ~= ARGV[6] then
	return 'VERSION_CONFLICT'
end

-- 当前项目的数据库库存字段是 INT。
-- 先校验整数格式及范围，避免 HINCRBY 在写入阶段因格式异常失败。
local publicQuotaText = redis.call('HGET', KEYS[1], 'quota')

if not publicQuotaText
or not string.match(publicQuotaText, '^%d+$') then
	return 'INVALID_DATA'
end

local publicQuota = tonumber(publicQuotaText)

if not publicQuota or publicQuota >= 2147483647 then
	return 'INVALID_DATA'
end

redis.call('HINCRBY', KEYS[1], 'quota', 1)

redis.call('HSET', KEYS[2],
	'state', 'RETURNED',
	'version', ARGV[7],
	'returnTaskId', ARGV[1]
)

return 'APPLIED'
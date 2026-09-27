-- KEYS[1]：申请记录Hash
-- KEYS[2]：待投递ZSet
--
-- ARGV[1]：订单编号
-- ARGV[2]：用户ID
-- ARGV[3]：活动ID
-- ARGV[4]：批次
-- ARGV[5]：报名记录ID

local requestsType = redis.call('TYPE', KEYS[1]).ok
local pendingType = redis.call('TYPE', KEYS[2]).ok

if requestsType ~= 'hash' then
	return 0
end

if pendingType ~= 'none' and pendingType ~= 'zset' then
	return -1
end

local requestJson = redis.call('HGET', KEYS[1], ARGV[1])

if not requestJson then
	return 0
end

local decoded, request = pcall(cjson.decode, requestJson)

if not decoded or type(request) ~= 'table' then
	return -1
end

-- 防止更新了不属于这条消息的申请
if request.orderId ~= ARGV[1]
or request.userId ~= ARGV[2]
or request.activityId ~= ARGV[3]
or request.epoch ~= ARGV[4] then
	return -1
end

-- 取消已经完成，旧的成功回写不允许覆盖取消结果
if request.status == 'CANCELLED' then
	if request.registrationId ~= ARGV[5] then
		return -1
	end
	return 2
end

if request.status == 'SUCCEEDED' then
-- 重复执行时，成功结果必须一致
	if request.registrationId ~= ARGV[5] then
		return -1
	end
elseif request.status == 'PENDING' then
	request.status = 'SUCCEEDED'
	request.registrationId = ARGV[5]
else
-- 不能覆盖取消、失败等其他状态
	return -1
end

local updatedJson = cjson.encode(request)

redis.call('HSET', KEYS[1], ARGV[1], updatedJson)
redis.call('ZREM', KEYS[2], ARGV[1])

return 1
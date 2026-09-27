-- KEYS[1]：待投递ZSet
-- KEYS[2]：申请记录Hash
-- ARGV[1]：再次投递的间隔，毫秒
-- ARGV[2]：可选，立即投递时指定订单；为空时领取任意到期订单

local retryMillis = tonumber(ARGV[1])

if not retryMillis or retryMillis <= 0 then
	return redis.error_reply('Invalid retry interval')
end

local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)

local orderId = ARGV[2]
if orderId and orderId ~= '' then
    local score = redis.call('ZSCORE', KEYS[1], orderId)
    if not score or tonumber(score) > now then
        return {}
    end
else
    local orders = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, 1)

    if #orders == 0 then
        return {}
    end

    orderId = orders[1]
end
local requestJson = redis.call('HGET', KEYS[2], orderId)

-- 先延后，避免其他实例立即领取同一个订单
redis.call('ZADD', KEYS[1], now + retryMillis, orderId)

-- 申请数据缺失时仍保留任务，由Java记录异常
return {orderId, requestJson or ''}

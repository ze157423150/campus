-- KEYS[1]：限流 ZSet 的 key
-- ARGV[1]：窗口长度，单位毫秒
-- ARGV[2]：窗口内最多允许通过的请求数
-- ARGV[3]：本次请求的唯一编号
--
-- 返回值：
-- 第一个元素：是否放行，1 放行，0 拒绝
-- 第二个元素：剩余可用次数
-- 第三个元素：建议等待时间，单位毫秒

local key = KEYS[1]
local windowMillis = tonumber(ARGV[1])
local maxRequests = tonumber(ARGV[2])
local requestId = ARGV[3]

-- 写入之前检查参数
if not key or key == '' then
	return redis.error_reply('Invalid rate limit key')
end

if not windowMillis or windowMillis <= 0
or windowMillis ~= math.floor(windowMillis) then
	return redis.error_reply('Invalid window size')
end

if not maxRequests or maxRequests <= 0
or maxRequests ~= math.floor(maxRequests) then
	return redis.error_reply('Invalid request limit')
end

if not requestId or requestId == '' then
	return redis.error_reply('Invalid request ID')
end

-- 使用 Redis 服务器时间，避免不同应用实例的时钟差异
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000
+ math.floor(tonumber(clock[2]) / 1000)

-- 只保留最近一个窗口内的放行记录
local cutoff = now - windowMillis
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)

-- 统计窗口内已经放行的请求数
local count = redis.call('ZCARD', key)

if count >= maxRequests then
-- 找到窗口内最早的一条记录，计算何时会腾出一个名额
	local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
	local oldestTime = tonumber(oldest[2])
	local retryAfterMillis = math.max(1, oldestTime + windowMillis - now)

	return {0, 0, retryAfterMillis}
end

-- 放行：记录本次请求
redis.call('ZADD', key, now, requestId)

-- 为整个key设置倒计时
redis.call('PEXPIRE', key, windowMillis)

local remaining = math.max(0, maxRequests - count - 1)

return {1, remaining, 0}
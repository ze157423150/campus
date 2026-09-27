-- KEYS[1]：令牌桶 Hash 的 key
-- ARGV[1]：桶容量，正整数
-- ARGV[2]：每秒补充的令牌数，正整数
--
-- 每次请求消耗1个令牌
--
-- 返回值：
-- 第一个元素：是否放行，1 放行，0 拒绝
-- 第二个元素：剩余可用的完整令牌数
-- 第三个元素：建议等待时间，单位毫秒

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])

if not key or key == '' then
	return redis.error_reply('Invalid rate limit key')
end

if not capacity or capacity <= 0
or capacity ~= math.floor(capacity) then
	return redis.error_reply('Invalid bucket capacity')
end

if not refillPerSecond or refillPerSecond <= 0
or refillPerSecond ~= math.floor(refillPerSecond) then
	return redis.error_reply('Invalid refill rate')
end

-- 使用 Redis 时间
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000
+ math.floor(tonumber(clock[2]) / 1000)

-- 读取上次保存的状态
local state = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens
local lastRefill

if not state[1] and not state[2] then
-- 第一次访问：使用满桶
	tokens = capacity
	lastRefill = now
else
	tokens = tonumber(state[1])
	lastRefill = tonumber(state[2])

	if not tokens or tokens < 0 or not lastRefill then
		return redis.error_reply('Invalid token bucket state')
	end
end

-- 防止时间回拨时重复补充令牌
local effectiveNow = math.max(now, lastRefill)
local elapsedMillis = effectiveNow - lastRefill

-- 按经过的时间补充，不能超过桶容量
local addedTokens = elapsedMillis * refillPerSecond / 1000
tokens = math.min(capacity, tokens + addedTokens)

local allowed = 0
local retryAfterMillis = 0

if tokens >= 1 then
	tokens = tokens - 1
	allowed = 1
else
-- 距离补足1个令牌还需要多久
	local clockDelayMillis = effectiveNow - now
	retryAfterMillis = clockDelayMillis
	+ math.ceil((1 - tokens) * 1000 / refillPerSecond)
end

-- 放行或拒绝，都保存本次计算后的状态
redis.call(
	'HSET',
	key,
	'tokens', tostring(tokens),
	'last_refill', tostring(effectiveNow)
)

-- 无请求后，等待足够补满整个桶的时间，再删除这个 key
local fullRefillMillis = math.ceil(capacity * 1000 / refillPerSecond)
local ttlMillis = math.max(1, fullRefillMillis + effectiveNow - now)
redis.call('PEXPIRE', key, ttlMillis)

return {allowed, math.floor(tokens), retryAfterMillis}
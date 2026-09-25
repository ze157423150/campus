-- One hash keeps metadata, quota, owner tokens and cancellation tombstones atomic.
-- ARGV: epoch, userId, orderId, expiresAtMillis
if redis.call('HGET', KEYS[1], 'ready') ~= '1' then return {'UNAVAILABLE'} end
if redis.call('HGET', KEYS[1], 'epoch') ~= ARGV[1] then return {'EPOCH_CHANGED'} end
if redis.call('HEXISTS', KEYS[1], 'x:' .. ARGV[3]) == 1 then return {'TERMINAL'} end
local owner = redis.call('HGET', KEYS[1], 'u:' .. ARGV[2])
if owner == ARGV[3] then
    return {'OK', redis.call('HGET', KEYS[1], 't:' .. ARGV[3])}
end
if owner then return {'DUPLICATE'} end
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
if now >= tonumber(ARGV[4]) then return {'EXPIRED'} end
if now < tonumber(redis.call('HGET', KEYS[1], 'start')) then return {'NOT_STARTED'} end
if now >= tonumber(redis.call('HGET', KEYS[1], 'end')) then return {'CLOSED'} end
local quota = tonumber(redis.call('HGET', KEYS[1], 'quota'))
if not quota or quota < 0 then return {'UNAVAILABLE'} end
if quota == 0 then return {'SOLD_OUT'} end
redis.call('HINCRBY', KEYS[1], 'quota', -1)
redis.call('HSET', KEYS[1], 'u:' .. ARGV[2], ARGV[3], 't:' .. ARGV[3], tostring(now))
return {'OK', tostring(now)}

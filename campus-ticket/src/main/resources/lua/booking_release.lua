-- Only the reservation owner can return its quota; retries are safe.
-- ARGV: epoch, userId, orderId
local epoch = redis.call('HGET', KEYS[1], 'epoch')
if not epoch then return 0 end
if epoch ~= ARGV[1] then return 1 end
-- Even if reservation has not yet arrived, prevent a delayed reserve from succeeding.
redis.call('HSET', KEYS[1], 'x:' .. ARGV[3], '1')
if redis.call('HGET', KEYS[1], 'u:' .. ARGV[2]) == ARGV[3] then
    redis.call('HDEL', KEYS[1], 'u:' .. ARGV[2], 't:' .. ARGV[3])
    redis.call('HINCRBY', KEYS[1], 'quota', 1)
end
return 1

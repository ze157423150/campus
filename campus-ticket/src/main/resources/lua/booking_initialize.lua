-- Called only by the fenced administrative recovery process, never on cache miss.
redis.call('DEL', KEYS[1])
for i = 1, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
end
return 1

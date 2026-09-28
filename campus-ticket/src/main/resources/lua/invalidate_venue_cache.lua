-- KEYS[1]：版本号key
-- KEYS[2]：场馆缓存key

--让对应版本号+1，并删除旧的缓存数据

redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])

return 1

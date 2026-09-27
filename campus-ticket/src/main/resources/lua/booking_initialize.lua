-- KEYS[1]：库存Hash
-- KEYS[2]：申请记录Hash
-- KEYS[3]：待投递ZSet
-- ARGV：按“字段名、字段值”成对传入

-- 已有库存，拒绝覆盖
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

-- 库存不存在，但申请或投递数据还在，不能直接重新初始化
if redis.call('EXISTS', KEYS[2]) == 1
or redis.call('EXISTS', KEYS[3]) == 1 then
    return -1
end

if #ARGV == 0 or #ARGV % 2 ~= 0 then
    return redis.error_reply('Invalid initialization arguments')
end

for i = 1, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
end

return 1
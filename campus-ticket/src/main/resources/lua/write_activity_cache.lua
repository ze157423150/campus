-- KEYS[1]：版本号key
-- KEYS[2]：活动缓存key
-- ARGV[1]：查询数据库前记录的版本号
-- ARGV[2]：要写入的缓存内容
-- ARGV[3]：缓存有效期，单位毫秒

local currentVersion = redis.call('GET', KEYS[1])

if not currentVersion then
	currentVersion = '0'
end

if currentVersion ~= ARGV[1] then
	return 0
end

redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])

return 1
-- KEYS: inventory, requests, quota. ARGV: taskId,quotaId,oldVersion,newVersion,operation,payloadJson
-- A per-version receipt survives subsequent transitions and makes stale retries harmless.
local a = cjson.decode(ARGV[6])
if redis.call('TYPE', KEYS[1]).ok ~= 'hash' or redis.call('TYPE', KEYS[3]).ok ~= 'hash' then return 'NOT_READY' end
local rt = redis.call('TYPE', KEYS[2]).ok
if rt ~= 'none' and rt ~= 'hash' then return 'INVALID_DATA' end
if redis.call('HGET',KEYS[1],'ready') ~= '1' then return 'NOT_READY' end
if redis.call('HGET',KEYS[1],'epoch') ~= a.epoch
 or redis.call('HGET',KEYS[3],'epoch') ~= a.epoch
 or redis.call('HGET',KEYS[3],'activityId') ~= a.activityId
 or redis.call('HGET',KEYS[3],'quotaId') ~= ARGV[2] then return 'QUOTA_MISMATCH' end

local receipt = 'task:' .. ARGV[4]
local done = redis.call('HGET',KEYS[3],receipt)
if done then
 if done ~= ARGV[1] then return 'TASK_CONFLICT' end
 return redis.call('HGET',KEYS[3],'result:' .. ARGV[4])
end
if redis.call('HGET',KEYS[3],'version') ~= ARGV[3] then return 'VERSION_CONFLICT' end
local state = redis.call('HGET',KEYS[3],'state')
local ownerField = 'u:' .. a.userId
local offerOwner = 'w:' .. a.offerId
local owner = redis.call('HGET',KEYS[1],ownerField)
local result = 'APPLIED'
local encoded = nil

if ARGV[5] == 'OFFER' then
 if state ~= 'HELD' then return 'STATE_CONFLICT' end
 if owner then result = 'OCCUPIED' end
elseif ARGV[5] == 'RELEASE' or ARGV[5] == 'CONFIRM' then
 if state ~= 'OFFERED' or redis.call('HGET',KEYS[3],'offerId') ~= a.offerId
  or redis.call('HGET',KEYS[3],'userId') ~= a.userId or owner ~= offerOwner then return 'OWNER_CONFLICT' end
 if ARGV[5] == 'CONFIRM' then
  if not a.orderId or not a.registrationId then return 'INVALID_DATA' end
  if redis.call('HEXISTS',KEYS[2],a.orderId)==1 or redis.call('HEXISTS',KEYS[1],'x:' .. a.orderId)==1 then return 'ORDER_CONFLICT' end
  encoded = cjson.encode({orderId=a.orderId,userId=a.userId,activityId=a.activityId,epoch=a.epoch,status='SUCCEEDED',registrationId=a.registrationId,waitlistOfferId=a.offerId})
 end
else return 'UNSUPPORTED' end

if ARGV[5] == 'OFFER' and result == 'APPLIED' then
 redis.call('HSET',KEYS[1],ownerField,offerOwner)
 redis.call('HSET',KEYS[3],'state','OFFERED','offerId',a.offerId,'userId',a.userId)
elseif ARGV[5] == 'RELEASE' then
 redis.call('HDEL',KEYS[1],ownerField)
 redis.call('HSET',KEYS[3],'state','HELD')
elseif ARGV[5] == 'CONFIRM' then
 redis.call('HSET',KEYS[1],ownerField,a.orderId)
 redis.call('HSET',KEYS[2],a.orderId,encoded)
 redis.call('HSET',KEYS[3],'state','CONSUMED','confirmedOrderId',a.orderId)
end
-- OCCUPIED still advances the version: the attempt was handled, but no seat was given.
redis.call('HSET',KEYS[3],'version',ARGV[4],receipt,ARGV[1],'result:' .. ARGV[4],result)
return result


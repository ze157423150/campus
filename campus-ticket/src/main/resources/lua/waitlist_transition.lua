-- 用途：将一个候补名额在 Redis 中推进到下一阶段。
-- Java 调用入口：WaitlistRedisService.transition(taskId, quotaId, version, operation, payload)。
-- KEYS 是 redis.execute 的 List.of(...) 中依次传入的 Redis key；ARGV 是其后依次传入的参数。
-- KEYS 和 ARGV 都从下标 1 开始。以下示例假设 activityId=1、quotaId=10。
--
-- KEYS[1]：活动报名库存 Hash，例 campus:booking:{1}:inventory。
--   ready：是否已初始化；epoch：库存批次。
--   u:{userId}：用户占用标记，普通报名时保存订单 ID，候补邀请时保存 w:{offerId}。
--   x:{orderId}：订单终态标记，本脚本确认报名时检查它，避免冲突。
--   quota：公开剩余名额数。本脚本不增减它，因为操作的是已被候补系统保留的名额。
-- KEYS[2]：报名请求 Hash，例 campus:booking:{1}:requests。
--   field 为订单 ID，value 为报名请求 JSON。
--   仅 CONFIRM 分支会在这里写入新订单对应的 SUCCEEDED 记录。
-- KEYS[3]：本次回收名额的 Hash，例 campus:booking:{1}:waitlist:quota:10。
--   保存 quotaId、activityId、epoch、state、version、offerId、userId 等字段。
--   注意：这是 Redis key，不是 MySQL 的 waitlist_quota 表；state 是 Redis 中的名额状态。
--
-- ARGV[1]：taskId，数据库 waitlist_redis_task.id，本次同步任务的唯一 ID。
-- ARGV[2]：quotaId，数据库 waitlist_quota.id，表示正在流转的那一个回收名额。
-- ARGV[3]：oldVersion，本次执行前 Redis 名额应有的版本，Java 传入 version - 1。
-- ARGV[4]：newVersion，本次处理后要记录的版本，Java 传入任务的 quotaVersion。
--   例如任务版本是 3，则要求 Redis 当前版本为 2，处理后改为 3。
--   同一任务重试时先检查执行记录，已处理则直接返回原结果，不要求当前版本仍为 2。
-- ARGV[5]：operation，操作类型（不是数据库记录的 status）：
--   OFFER：为候补邀请占用名额，Redis 名额 HELD -> OFFERED，用户占用设为 w:{offerId}。
--   RELEASE：释放已关闭邀请的占用，Redis 名额 OFFERED -> HELD，清除该用户占用。
--   CONFIRM：将邀请占用转为正式报名，Redis 名额 OFFERED -> CONSUMED，用户占用改为订单 ID。
--   HOLD 和 RETURN 由其他脚本处理，不传给本脚本。
-- ARGV[6]：payloadJson，JSON 字符串；下一行解析后使用 a.xxx 读取字段：
--   activityId：活动 ID，用于核对名额所属活动。
--   epoch：库存批次，用于防止旧批次任务操作新批次库存。
--   offerId：数据库 waitlist_offer.id，本次操作针对的邀请 ID。
--   userId：本次邀请所属的用户 ID。
--   orderId：确认候补成功后创建的新报名订单 ID，仅 CONFIRM 必须提供。
--   registrationId：确认成功后对应的 registration.id，仅 CONFIRM 必须提供。
--   OFFER / RELEASE 通常不传最后两个字段；Java 在它们为 null 时直接省略字段。
--   Java 将所有字段值转成字符串，以避免 Lua 将大整数 ID 解析为浮点数造成精度丢失。
--   OFFER 示例：{"activityId":"1","epoch":"1","offerId":"20","userId":"8"}。
--
-- 处理结果与重试：
--   APPLIED：本次操作成功应用。
--   OCCUPIED：仅 OFFER 返回，用户已有占用；不分配名额，名额仍为 HELD，但推进版本。
--   每个已处理版本保存 task:{newVersion}=taskId 和 result:{newVersion}=处理结果。
--   即使名额后来已推进到更高版本，旧任务重试仍可返回原结果，避免重复修改。
--   其余返回值表示校验不通过，Java 会抛出异常，任务不会被正常标记为 DONE。
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

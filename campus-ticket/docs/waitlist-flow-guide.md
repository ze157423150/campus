# 候补递补与名额回流：按一次取消流程读代码

配套阅读：[状态字典](data-model.md)、[报名 API](booking-api.md)、[同步恢复](booking-redis-recovery.md)。本篇重点解释已成功报名后的取消与候补；入口现已支持 `joinWaitlistIfFull=true` 满额自动候补。

## 先运行什么
候补增量脚本为 `sql/20260928_waitlist_complete.sql`，包含任务 result_code 与站内通知表。历史测试环境已经执行；新环境请先按 [启动说明](setup.md) 初始化。
其他数据库先依次具备原业务表与异步报名表，再执行这个脚本。脚本支持重复执行，不重建已有表。
使用 IDEA 重启应用，并继续启用已有的 `async` profile。后台候补任务及延迟队列组件均在该 profile 下启动。
候补确认时间在 application-async.properties 中配置，默认300秒；实际截止时间不晚于报名截止或活动开始。

普通报名仍为 Redis 预占 -> Kafka -> 数据库成功，无需二次确认。
只有候补邀请需要用户确认。通知采用站内持久化通知，可通过接口查询；未发送短信、邮件，也没有增加前端。
跨实例 L1 主动失效仍按原约定暂缓。

## 五张表，五个问题
|表|回答的问题|
|---|---|
|activity_waitlist|谁在排队，排队记录是否有效？|
|waitlist_quota|A取消产生的这一个名额，当前处于什么状态？|
|waitlist_offer|这一次邀请发给谁，何时到期，是否确认？|
|waitlist_redis_task|哪个版本的Redis操作还没同步，执行结果是什么？|
|waitlist_notification|用户可以看到哪条邀请通知，是否已读？|

quota_id始终跟随一个释放名额；offer_id每次递补都不同。
数据库 quota.version 表示已经决定的流转版本，Redis版本可能暂时落后；任务按版本顺序补齐。

## 示例：A取消，B超时，C确认

初始：活动1个名额，A已报名，B、C依次候补，公开库存为0。

### 第一步：A取消
入口：
`POST /activities/{activityId}/booking-orders/{orderId}/cancel`

阅读 `BookingCancellationService.cancelInDatabase()`：
1. 从UserHolder取A，不信任前端传来的用户ID。
2. 锁活动、原订单、报名记录，检查归属和状态。
3. 将A报名与订单改为CANCELLED。
4. 创建 quota=HELD/version0 和 HOLD同步任务。
5. 以上在一个MySQL事务中提交。此时不增加数据库公开库存。

然后 `BookingRedisSyncService.synchronize()` 发现该订单有关联quota，调用候补协调器。
没有quota的历史取消订单仍走旧补偿路径。Kafka迟到的取消订单消息也调用这个统一入口，不能直接旧Lua加库存。

### 第二步：Redis接管原名额
阅读 `WaitlistCoordinator.progress()` 和 `WaitlistRedisTaskProcessor.tryProcess()`：
先处理当前名额最早未完成的任务，再推进数据库状态。
协调器不包数据库事务，每个具体数据库步骤单独提交；不能把Redis调用包进取消事务。

`waitlist_hold.lua`：
核对订单、用户、epoch及当前占用，删除A的u:用户ID，取消原Redis申请并清理pending。
创建名额Redis Hash（HELD/version0）。公开quota不增加。
HOLD重复任务仅返回已执行，不会影响A后来报名的新订单。

### 第三步：为B准备邀请
阅读 `WaitlistWorkflowService.advance()` 的HELD分支：
1. 只有先前Redis任务全部完成，才继续。
2. 检查活动仍能报名；按候补ID挑选队首。
3. 已经在数据库报名成功的候补会被跳过。
4. 创建 PREPARING 邀请，把排队记录改为OFFERED，quota指向该邀请/version+1。
5. 同事务创建OFFER任务。

PREPARING 是新增的内部准备状态：此时不能确认，也不通知，不开始确认倒计时。
排队记录中的OFFERED表示已选中，不代表Redis一定完成；查询接口额外返回offerStatus区分。

### 第四步：Redis占位并通知B
`waitlist_transition.lua` 的OFFER分支原子检查 u:B：
- 不存在：写入 `u:B = w:邀请ID`，标记名额OFFERED。
- 已存在：普通报名可能在候补校验后先预占成功。返回OCCUPIED并记录任务回执；不抢走其现有名额。

OCCUPIED也是“本次分配尝试已处理”的结果，推进Redis版本，但名额仍为HELD。
数据库随后关闭无效邀请，取消该候补，继续下一位，不发送错误通知。

OFFER成功后，advance将PREPARING激活为OFFERED，使用数据库当前时间设置确认截止时间。
邀请激活与站内通知一起提交。事务内发布 WaitlistOfferReadyEvent，AFTER_COMMIT 监听器在提交后向 Redisson 延迟队列加入邀请 ID。
Redis保留先完成，通知后生成，避免用户收到通知却没有名额。

### 第五步：B超时
`WaitlistDelayService` 将邀请ID延迟投递到ready队列，定时非阻塞读取。
队列消息只触发检查，不直接判定超时；权威依据仍是数据库状态和截止时间。

调用协调器后，advance发现B到期：
- B邀请/候补改为EXPIRED；
- quota回到HELD，版本+1；
- 同事务写RELEASE任务。

RELEASE Lua只删除仍指向B这次邀请的占用，名额留在候补流程，不增加公开库存。
完成后再按顺序创建C的新邀请，重复第三、四步。
旧B的延迟消息晚到或重复到达，不会关闭C的邀请。

### 第六步：C确认
入口：`POST /waitlist-offers/{offerId}/confirm`

阅读 `WaitlistWorkflowService.confirm()`：
锁活动、quota、邀请和候补，检查当前登录用户、邀请是否为当前邀请、活动状态及截止时间。
创建/恢复registration，创建SUCCEEDED订单，邀请/候补变为CONFIRMED，quota变为CONSUMED，记录CONFIRM任务。
这里不扣数据库库存，领取的是保留名额，不是再抢一个公开名额。
Redis CONFIRM 将 `u:C = w:邀请ID` 转换为正式订单ID，建立成功订单的Redis记录，同样不扣公开库存。
重复确认返回同一个订单ID。该成功订单以后也可以正常取消，产生新的quota流转。

### 如果C也不确认且没人候补
RELEASE完成后，advance找不到WAITING用户：
数据库quota=RETURNED/version+1，activity.remaining_quota+1，创建RETURN任务，同事务提交。
例外：管理员取消活动的旧逻辑已重置数据库活动库存时，不再重复增加数据库库存；这不代表管理员取消的全部异步订单同步已经完善，见 [功能边界](limitations.md)。
`waitlist_return.lua` 核对HELD和旧版本，公开库存+1并保存任务回执。
Redis已成功但数据库任务标记失败，再执行也不会多加库存。

## 自动恢复
`WaitlistRecoveryJob` 每轮先扫描到期的PENDING同步任务，再按游标推进活跃quota。
已CONSUMED/RETURNED的quota仍可能有任务未完成，所以任务扫描独立于活跃quota扫描。
领取任务会推迟下一次尝试；进程退出后可重新领取。超过领取间隔可能重复执行，Lua回执确保幂等。

延迟消息入队失败、消息取出后进程退出、延迟队列消息丢失，都由数据库截止时间扫描补漏。
队列不是唯一可靠凭据，也不保证截止的同一毫秒就完成回收。确认接口即使队列未处理，超过期限也拒绝确认。
活动结束/取消后停止通知新的候补，关闭邀请并处理释放。等待队列也会清理。
Redis永久数据丢失/手工改坏库存时只保留失败任务并记录日志，不凭空猜测库存；有保留名额时禁止用原初始化接口重建。

当前代码使用 Redisson 延迟队列，并辅以数据库补漏；队列消费不具有本项目实现的逐条确认协议。不要把延迟触发本身当作唯一可靠的超时凭据。

## API顺序
所有用户接口都需要 `Authorization: Bearer <token>`，用户ID从登录上下文获取。

|方法|路径|用途|
|---|---|---|
|POST|/activities/{activityId}/waitlist|加入候补|
|GET|/activities/{activityId}/waitlist/me|有效候补、前方人数、offerId、offerStatus、确认截止时间|
|POST|/activities/{activityId}/waitlist/{waitlistId}/cancel|仅退出WAITING排队|
|GET|/waitlist-offers/{offerId}|查询自己的邀请|
|POST|/waitlist-offers/{offerId}/confirm|确认邀请，返回正式订单ID|
|POST|/waitlist-offers/{offerId}/decline|放弃已激活邀请，系统递补|
|GET|/users/me/waitlist-notifications?afterId=0&limit=20|按ID升序查询站内通知|
|POST|/users/me/waitlist-notifications/{id}/read|标记自己的通知已读|

查询其他人的邀请/通知返回404。通知显示当前邀请状态，已过期的旧通知不能用于确认。
PREPARING邀请不可确认或放弃，等待准备结束后再操作；后台故障会延后激活，不消耗用户确认时间。

## 推荐断点（按上面顺序看，不必先读所有Mapper）
1. BookingCancellationService：创建WaitlistQuota处。
2. WaitlistRedisTaskProcessor：调用Redis Lua之前。
3. WaitlistWorkflowService.advance：HELD分支创建邀请处。
4. advance：db.activate处，看PREPARING如何变成OFFERED。
5. WaitlistDelayService.poll：收到到期邀请ID。
6. WaitlistWorkflowService.release：B超时后创建RELEASE任务。
7. WaitlistWorkflowService.returnToPublic：无人候补时数据库库存回补。
8. WaitlistWorkflowService.confirm：C确认，注意没有deductQuota。

## 历史验证与最近增量

下面 10 项及 49 项总数是早期候补版本的历史记录。2026-09-30 增加自动候补验证后，专项执行为候补集成 14 项、报名编排单元测试 7 项，共 21 项通过。该轮 Kafka 投递被隔离，详见 [测试说明](testing.md)。
真实MySQL/Redis/HTTP集成测试10项通过：
- 无候补取消回补一次、HOLD/RETURN旧任务重放；
- FIFO、重复加入、退出重排、放弃递补、确认后再次取消；
- Redisson真实延迟投递、数据库扫描补漏；
- Redis执行成功而数据库未确认时重放，PREPARING不得确认或发通知；
- 普通报名先预占的候补被安全跳过；
- 8线程确认/放弃竞争，只有一种最终结果；
- 过期确认拒绝、活动停止报名后不再递补；
- HTTP登录、邀请归属、通知已读及重复确认；
- Redis校验失败保留任务，恢复后不需用户重新提交；
- 数据库中名额记录创建失败时整个取消事务回滚。

最后一轮Maven共发现67项：实际通过49项，18项其他可选集成测试未开启；失败/错误0。
本轮没有重跑Kafka真实投递压测；测试中使用真实报名预占与消费Service构造成功订单。
测试只清理独立创建的数据，保留新增表及字段。

复跑：
```powershell
$env:CAMPUS_WAITLIST_INTEGRATION_TESTS='true'
mvn -o '-Dmaven.compiler.proc=full' test
```

这是一轮功能及故障窗口验证，不是候补接口的大规模吞吐量基准测试。


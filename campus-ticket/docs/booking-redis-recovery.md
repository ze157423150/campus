# Redis 同步与中断恢复

## 哪些操作需要补偿

MySQL 事务不能同时原子提交 Redis。代码先提交正式业务结果及同步标记/任务，再尝试 Redis；任务保存在数据库，应用恢复后可继续处理。

| 场景 | 恢复入口 | 处理方式 |
| --- | --- | --- |
| 预占后立即投递未完成 | BookingDispatchJob | 扫描各活动 pending，claim 后补投 Kafka |
| 消费数据库提交后 Redis 未同步 | BookingRedisSyncJob | 扫描终态 redis_dirty=1 订单，领取后调用 synchronize |
| 取消后名额接管中断 | 上述同步任务、WaitlistRecoveryJob | 根据来源订单找到 quota，重放 HOLD 并继续推进 |
| OFFER/RELEASE/CONFIRM/RETURN 同步中断 | WaitlistRecoveryJob | 重放 PENDING Redis 任务，按版本及回执幂等执行 |
| 延迟队列消息遗漏或处理时中断 | WaitlistRecoveryJob | 扫描活跃 quota/邀请，重新检查时间与业务状态 |
| Kafka 消费异常 | 消费重试及死信处理 | 检查错误和死信，不应视为所有异常都能自动成功恢复 |

## synchronize 的分支

`BookingRedisSyncService.synchronize` 根据数据库订单终态选择处理：成功同步正式报名结果；失败执行 markFailed；取消时先检查是否有来源 quota。有 quota 时调用协调器接管名额，确认初始 HOLD 任务已完成后再 clean 原订单同步标记；没有 quota 时走旧数据兼容的 markCancelled。

因此这个类负责所有订单终态同步，不仅是取消业务。`bookingMapper.clean(orderId)` 清除的是数据库 redis_dirty，不是删除报名记录或 Redis requests。

## 候补协调循环

1. 优先查当前 quota 的待执行同步任务，按重试时间领取。
2. HOLD 将 A 的取消结果同步 Redis，把名额交给候补流程，不增加公开库存。
3. 没有待同步任务时 advance 根据 quota 状态分配 B，建立 PREPARING 邀请及 OFFER 任务。
4. OFFER Lua 预占 B；如果 B 已有占用，返回 OCCUPIED，advance 结束该邀请后继续寻找候补。
5. 成功占用后 advance 激活 OFFERED、记录通知并发布事件，等待确认或超时。
6. B 确认产生 CONFIRM；拒绝/超时产生 RELEASE；无人可分配产生 RETURN。

循环有次数上限，不会在一次 HTTP 请求里无限等待。尚未完成的持久化任务由后台继续处理。RELEASE 只清理本次邀请的占用并恢复待分配状态；RETURN 才回补公开库存。

## 任务周期

async profile 当前订单 Redis 同步约每 5 秒执行一次，每批最多 50 条，失败后按重试时间再次处理。候补恢复约每 3 秒执行，延迟队列消费者约每 200 毫秒轮询。参数以 application-async.properties 和各 Job 注解为准。领取任务会延后可重试时间，因此恢复并不总在下一次扫描立即发生。

## 排查顺序

先查 booking_order 的 status、redis_dirty、失败原因和日志；再查来源 waitlist_quota 的 status/version/offer_id；检查该 quota 的 waitlist_redis_task 状态、重试时间与 result_code；最后核对 Redis epoch、用户占用、quota 版本及 pending。使用只读查询保留现场，不直接将任务批量标 DONE。

这些任务属于业务补偿扫描，并非全量账本核对或 Redis 灾难恢复。Redis 整库丢失、数据被手工覆盖、永久数据库错误或任务载荷损坏仍需专门处理。公开活动缓存中的剩余数也可能短暂滞后，不应拿缓存快照直接证明实时库存不一致。

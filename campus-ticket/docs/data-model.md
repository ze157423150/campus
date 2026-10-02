# 数据与状态字典

本文件解释模型职责，不替代建表 SQL。字段类型、索引与约束以 [基础表](../sql/schema.sql)、[异步表](../sql/20260924_async_booking.sql)、[候补表](../sql/20260928_waitlist_complete.sql) 为准。

## 表的分工

| 表 | 作用 |
| --- | --- |
| campus_user | 身份、角色、密码哈希 |
| activity | 活动资料、报名时段、数据库剩余名额 |
| registration | 用户与活动的正式报名关系，可取消后恢复 |
| venue | 场馆资料与开放状态 |
| booking_inventory | 异步库存初始化信息与批次标识 |
| booking_order | 一次报名请求的持久化结果、失败原因、Redis 同步标记 |
| booking_order_log | 订单处理与状态变化日志 |
| booking_outbox | 保留的早期数据库投递模型表；当前 Redis 优先链路不靠它扫描投递 |
| activity_waitlist | 用户排队记录和有效候补约束 |
| waitlist_quota | 一个取消后待流转的名额，关联来源订单、当前邀请和版本 |
| waitlist_offer | 每次邀请、接收人、确认期限及结果 |
| waitlist_redis_task | 数据库提交后的 Redis 同步任务与回执 |
| waitlist_notification | 站内邀请通知与已读记录 |

registration 回答“谁报名了哪个活动”，booking_order 回答“哪次请求以什么结果完成”。quota 跟踪可多次递补的同一个名额，offer 跟踪某一次邀请，两者不是重复表。

## 业务状态

| 对象 | 状态 | 含义 |
| --- | --- | --- |
| activity | DRAFT / PUBLISHED / CANCELLED | 草稿 / 已发布 / 已取消 |
| registration | REGISTERED / CANCELLED | 正式报名 / 已取消 |
| booking_order、Redis request | PENDING / SUCCEEDED / FAILED / CANCELLED | 处理中 / 成功 / 失败 / 取消；Redis 优先受理时数据库可能尚无订单行 |
| activity_waitlist | WAITING | 等待分配 |
| activity_waitlist | OFFERED | 已选中进入邀请流程，不代表邀请已可确认 |
| activity_waitlist | CONFIRMED | 已接受并转为正式报名 |
| activity_waitlist | CANCELLED | 用户取消、资格变化或活动关闭等结束 |
| activity_waitlist | EXPIRED | 邀请超时结束 |
| waitlist_offer | PREPARING | 已创建，正在准备 Redis 占用与邀请 |
| waitlist_offer | OFFERED | 已激活，等待用户限时确认 |
| waitlist_offer | CONFIRMED / CANCELLED / EXPIRED | 已确认 / 已取消或拒绝 / 超时 |
| waitlist_quota | HELD | 候补流程持有，待分配 |
| waitlist_quota | OFFERED | 绑定当前邀请 |
| waitlist_quota | CONSUMED | 邀请确认，转成正式报名 |
| waitlist_quota | RETURNED | 已归还公开库存 |
| waitlist_redis_task | PENDING / DONE | 尚待同步 / 同步完成 |
| venue | OPEN / CLOSED | 对外开放 / 关闭 |

任务操作 HOLD、OFFER、RELEASE、CONFIRM、RETURN 不是任务状态。任务 `result_code`（如 APPLIED、OCCUPIED）是执行回执，也不是 quota 状态。不要把“任务 DONE”理解成整个名额递补业务结束：OFFER 任务完成后还需要 advance 激活邀请。

`active_flag` 配合 `(activity_id,user_id,active_flag)` 唯一索引限制一个有效候补。历史结束记录使用 NULL；MySQL 唯一索引允许多行 NULL，因此可以保留多次历史记录。重新候补仍需业务校验已有有效记录。

## Redis 数据

键定义集中在 [RedisConstants.java](../src/main/java/com/campus/ticket/constants/RedisConstants.java)。

| 键 | 数据与用途 |
| --- | --- |
| `campus:login:<token>` | 登录身份及密码指纹相关会话信息 |
| `campus:booking:{activityId}:inventory` | 库存、epoch、用户占用等 Hash 字段 |
| `campus:booking:{activityId}:requests` | orderId 对应申请 JSON，包括受理时间、到期时间和状态 |
| `campus:booking:{activityId}:pending` | ZSet；member 为订单，score 为下次允许投递时间 |
| `campus:booking:{activityId}:waitlist:quota:<quotaId>` | 候补名额状态、版本和任务回执 |
| `campus:activity:published:detail:v3:<id>` | 活动详情 L2 缓存 |
| `campus:venue:public:detail:v1:<id>` | 场馆详情 L2 缓存 |
| `campus:bloom:activity:v1` | Redisson 管理的活动布隆过滤器 |
| `campus:rate-limit:sliding:...` | 滑动窗口访问记录 |
| `campus:rate-limit:bucket:...` | 令牌桶状态 |
| `campus:waitlist:{delay}:ready` | Redisson 候补超时队列入口，另有其管理的内部键 |

同一活动的 `{activityId}` hash tag 将相关 Redis 键放入同一槽，支持同槽 Lua 操作。不要手工删除库存键来“修复”单条订单，这可能破坏其他用户的预占状态。

`acceptedAtMillis` 是受理时间，`expiresAtMillis` 是该申请的业务处理期限，后者应更大；它不是整个 requests Hash 的 TTL。pending 删除一个 member 也不会自动删除 requests 中的历史结果。

## 一致性字段

- `booking_order.redis_dirty=1`：数据库终态还需要同步 Redis；成功同步后 clean 改为 0。后台扫描只处理符合状态与重试时间条件的记录。
- `quota.version`：同一个名额每次流转递增。Lua 的 expectedVersion 通常为新版本减一，检查当前 Redis 状态是否恰好是此次流转的前置版本。
- 任务 ID 与回执：脚本执行成功、数据库尚未标 DONE 就中断时，重放能够识别已执行，避免库存重复增加。
- `invalidationVersion`：缓存失效与异步重建的竞争控制；不承担报名库存版本控制。

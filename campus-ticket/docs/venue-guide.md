# 场馆资料管理与两级缓存

文档总入口：[导航](README.md)。本篇保留场馆实现细节，末尾 2026-09-28 结果为历史验证；当前运行与复跑方式见 [启动](setup.md) 和 [测试](testing.md)。

## 业务范围

场馆是独立资料，不改变 activity.location，也未引入活动与场馆的外键关系、时段预约或冲突检测。

公开详情和分页只展示 OPEN 场馆；CLOSED 场馆对公开详情返回 404。管理员可查看全部场馆，创建或以 PUT 完整修改资料。关闭和重新开放通过修改 status 完成，不提供物理删除。

管理员权限在 VenueService 中校验，沿用 TokenAuthenticationFilter 和 UserHolder。未登录返回 401，学生越权返回 403；参数不合法返回 400。

## 数据库

已有库执行 `sql/20260928_add_venue.sql`，只新增 venue 表。空库的 `sql/schema.sql` 也包含此表，不要对已有数据库执行整份 schema.sql。

字段：id、name、address、capacity、description、status、create_time、update_time。status 为 OPEN/CLOSED；name 最长100字，address 最长200字，description 最长2000字，capacity 为1～1000000。写接口必须明确传入 status，避免更新资料时意外重新开放。

## API

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | /venues/{id} | 公开 | 开放场馆详情，使用两级缓存 |
| GET | /venues?page=1&pageSize=10&keyword=报告厅 | 公开 | 开放场馆分页，名称/地址关键词搜索 |
| GET | /venues/{id}/management | 管理员 | 详情直接查数据库，包括关闭场馆 |
| GET | /venues/management?page=1&pageSize=10&status=CLOSED | 管理员 | 管理分页，status 可省略查询全部 |
| POST | /venues | 管理员 | 创建，返回201和 venueId |
| PUT | /venues/{id} | 管理员 | 完整更新，返回200和消息 |

POST/PUT 请求示例：

```json
{
  "name": "第一报告厅",
  "address": "图书馆二层",
  "capacity": 200,
  "description": "配备投影和扩音设备",
  "status": "OPEN"
}
```

管理员请求携带 `Authorization: Bearer <管理员token>`。分页每页最多100条，关键词最多100字。名称不要求唯一，避免把不同校区的同名场馆误当成同一个。

## 查询流程

VenueController → VenueService（参数与权限）→ VenueCacheService。

1. 查询 VenueLocalCache（Caffeine），命中后解析原始 JSON；空字符串代表不存在。
2. L1 未命中，查询 Redis。读取前记录本地失效版本，回填时版本变化则放弃写入，防止本实例旧查询在失效之后回填。
3. L2 未命中，使用 Redisson 场馆维度的锁，最多等待2秒；再次检查 L2，仍没有才查 VenueMapper.findOpenById。
4. 数据不存在或场馆关闭：缓存空字符串10秒。正常数据的逻辑有效期为30秒加0～10秒随机偏移，Redis 物理有效期2分钟。
5. L1/L2 命中但逻辑过期：返回旧值，VenueRefreshDispatcher 使用有界线程池提交后台重建；同实例同ID去重，跨实例用 Redisson 锁互斥。
6. 后台再次检查 Redis 是否仍需刷新，再查询数据库。写入前由 Lua 比较版本号，版本变化则拒绝旧结果写入。

数据库回源结果先写 L2，后续请求读取 L2 时回填 L1。L1 默认最多500条、写入后2秒失效，读取不会刷新TTL。

分页直接查询数据库，避免对任意关键词和页码建立大量缓存。场馆当前使用空值缓存防穿透；活动已有的布隆过滤器不用于场馆，两个模块ID空间独立。

## 修改后的失效流程

VenueService.create/update 在数据库事务内写入，更新前按主键 FOR UPDATE 确认场馆存在并串行化同一条资料更新。随后发布 VenueChangedEvent。

VenueCacheListener 仅在 AFTER_COMMIT 阶段处理事件：先清本实例L1，Lua原子执行版本号加一和删除L2，finally再次清L1并推进本地失效版本。异步重建写入L2成功后也清理本实例L1。

事务回滚不会触发缓存失效。提交后的Redis失效异常会记录日志，数据库已经提交，不向用户伪装为回滚。此时旧值可能保留到TTL到期；没有新增持久化的失效补偿任务。

Redis key 均在 RedisConstants 中统一定义：

```text
campus:venue:public:detail:v1:{id}
campus:venue:cache:version:{id}
campus:lock:venue:rebuild:{id}
```

上面 `{id}` 是文档中的替换占位符，实际key后缀为数字；当前运行在单机 Redis，未声称支持 Redis Cluster 多key脚本。

跨实例 L1 主动通知按当前任务约定暂缓。其他实例可能暂时展示旧资料或旧空值，依靠短TTL重新加载；逻辑过期也允许短暂返回旧值。这不是强一致的开放权限判断，场馆状态在本模块中属于展示资料。

## 验证方式

纯单元测试：

```powershell
mvn -o '-Dmaven.compiler.proc=full' '-Dtest=VenueServiceTest' test
```

真实 HTTP/MySQL/Redis 集成测试（使用项目配置）：

```powershell
$env:CAMPUS_VENUE_INTEGRATION_TESTS='true'
mvn -o '-Dmaven.compiler.proc=full' '-Dtest=VenueServiceTest,VenueIntegrationTest' test
```

集成测试执行新增表迁移，并创建、清理独立测试场馆和账号，不清空原有数据。venue表会保留供实际业务使用。测试禁用报名任务，并替换无关的活动布隆过滤器组件，避免测试场馆时扫描活动数据。

覆盖管理员权限、参数校验、公开/管理分页隔离、L1/L2命中、关闭后空值与重新开放、版本校验拒绝旧写入、20并发冷查询只回源一次，以及逻辑过期时先返回旧值再异步重建。

2026-09-28 验证结果：场馆单元测试5项、真实集成测试5项，以及 AdminWorkflowTest、BookingDispatchServiceTest、BookingQueryServiceTest、BookingRedisSyncTest 共32项回归测试全部通过（合计42项）。20个并发冷查询全部返回200，场馆回源SQL执行1次。测试数据已清理，当前配置数据库中的venue表已创建。

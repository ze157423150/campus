# 校园活动预约平台

面向校园讲座、文体赛事、社团活动的后端学习项目。目前为同步报名的基础版本：Spring Boot、MyBatis、MySQL，Redis用于登录会话与活动详情缓存。没有支付、Kafka异步订单、Lua名额预扣或Caffeine多级缓存。

## 运行

1. 准备 JDK 17或更高版本、Maven、MySQL 8、Redis。IDEA启用Lombok注解处理。
2. 新环境创建 `campus_ticket` 数据库，执行 `sql/schema.sql`。已有开发库不要重建；当前开发库已完成category迁移。
3. 在本地配置 `src/main/resources/application.properties` 的数据库、Redis连接信息。也可在IDEA运行配置中用环境变量覆盖：`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_DATA_REDIS_PASSWORD`。不要将实际密码发布到公开仓库。
4. 在IDEA运行 `CampusTicketApplication`，访问 `http://localhost:8081/health`。端口以实际 `server.port` 配置为准。
5. 用 `requests/account-registration.http` 注册并登录。普通注册只能生成STUDENT账号。首次管理员请先注册专用账号，再由数据库维护者执行 `UPDATE campus_user SET role='ADMIN' WHERE student_no='替换为专用管理员学号';`，确认影响行数为1。项目不内置默认管理员密码。
6. 使用 `requests/activity-lifecycle.http` 创建、发布活动并完成报名与取消。请求文件中的Token和账号占位值需要自行替换。

本项目暂不需要前端即可演示。时间字段使用服务器本地时间，示例格式为 `2026-10-01T14:00:00`；演示时应改为合适的未来日期。

## 接口与业务

- [完整接口清单及活动规则](docs/backend-api.md)
- [账号与报名查询](docs/account-registration-api.md)
- [分类、关键词与报名阶段筛选](docs/activity-search-api.md)

活动生命周期为 DRAFT → PUBLISHED → CANCELLED，草稿也可直接取消。只有草稿可编辑，取消后不再发布。报名在REGISTERED和CANCELLED之间转换；重新报名复用原记录，因此create_time是首次报名记录的创建时间。

报名、个人取消、管理员取消活动均在数据库事务中先锁活动行，再修改报名与名额。同一活动的写操作会串行等待；当前保证正确性，但热门活动存在行锁竞争，这是后续优化的基线。

## 自动验证

集成测试连接配置中的真实MySQL和Redis，使用随机HTTP端口，只创建和清理自己的测试数据。请在开发/测试库运行，勿指向生产库。正常结束后清理测试记录；进程强制终止可能遗留测试数据。数据库自增序列不会回退。

PowerShell执行：

```powershell
$env:CAMPUS_INTEGRATION_TESTS = 'true'
try {
    mvn '-Dmaven.compiler.proc=full' test
} finally {
    Remove-Item Env:CAMPUS_INTEGRATION_TESTS -ErrorAction SilentlyContinue
}
```

`proc=full`用于在较新JDK上显式启用Lombok注解处理。未开启该环境变量时业务集成测试跳过，原有contextLoads测试仍会启动Spring上下文。测试包括账号权限、密码失效、筛选分页、活动生命周期、取消和重新报名，以及同时争抢名额/重复报名。

并发正确性测试不代表吞吐量测试，目前没有宣称任何QPS或性能提升数字。

## 后续学习路线

先固定测试环境并记录同步报名的吞吐量、P95/P99延迟、成功/失败数量及数据库状态；随后逐步学习Redis与Lua原子预扣、Kafka异步落库、消费幂等、失败补偿和状态查询。每一步都保留正确性验证与同条件性能对比。尚未实现的设计不作为已完成成果写入简历。

# 校园活动票务与预约平台

面向校园讲座、文体赛事和社团活动的后端学习项目。使用 Spring Boot、MyBatis、MySQL、Redis、Lua、Kafka、Caffeine 和 Redisson，实现活动管理、异步报名、满额自动候补、候补邀请确认和超时递补。

## 已实现的主要能力

| 模块 | 当前实现 |
| --- | --- |
| 基础业务 | 注册登录、个人资料、密码修改、活动与场馆管理、报名查询 |
| 流量控制 | Redis + Lua 滑动窗口、令牌桶；IP、用户及报名接口粒度 |
| 多级缓存 | Caffeine L1 + Redis L2；活动布隆过滤器、空值缓存、互斥锁、逻辑过期和异步重建 |
| 异步报名 | Redis 原子预占、立即投递 Kafka、定时补投、消费幂等、订单日志和 Redis 同步补偿 |
| 候补递补 | 满额自动候补、FIFO 分配、限时确认、Redisson 延迟队列、数据库扫描补偿、无人候补时回流公开库存 |

普通报名受理成功不代表已落库，需要查询订单状态。候补确认直接通过数据库事务生成成功报名，再同步 Redis，不重新走普通抢票的 Kafka 链路。

## 文档入口

从 [文档导航](docs/README.md) 开始。首次阅读建议按以下顺序：

1. [环境与启动](docs/setup.md)
2. [完整 API 清单](docs/backend-api.md) 与 [报名、候补接口说明](docs/booking-api.md)
3. [架构与代码导航](docs/architecture.md)
4. [数据与状态字典](docs/data-model.md) 与 [候补流程详解](docs/waitlist-flow-guide.md)

请求示例位于 [requests](requests)。无需付费 HTTP Client，可使用 Postman、Apifox 或 PowerShell，见演示文档。

## 快速运行

准备 JDK 17+、Maven、MySQL、Redis 和 Kafka。新库先执行 `sql/schema.sql`，再执行异步报名及候补建表脚本，详细顺序见启动文档。配置连接信息后，在 IDEA 启用 `async` profile，运行 `CampusTicketApplication`。

默认健康检查：`GET http://localhost:8081/health`。首次管理员由数据库维护者将指定已注册账号的 `role` 改为 `ADMIN`。创建活动后使用 `publish-with-inventory` 接口发布并初始化 Redis 库存。


# 环境与启动

## 依赖

项目使用 Java 17、Spring Boot 4.1.1、MyBatis Starter 4.1.0、Redisson 4.7.0；以 [pom.xml](../pom.xml) 为版本来源。准备 MySQL、Redis、Kafka 和 Maven；IDEA 开启 Lombok 注解处理。

Kafka 单节点开发配置位于 [docker-compose.kafka.yml](../deploy/docker-compose.kafka.yml)。在该文件所在目录执行 `docker compose -f docker-compose.kafka.yml up -d`。`KAFKA_ADVERTISED_HOST` 必须是 Java 客户端可访问的主机地址。当前示例使用虚拟机 `192.168.100.128`，Kafka 9092、MySQL 3306、Redis 6379；环境不同请覆盖。

## 数据库初始化

新建 `campus_ticket` 数据库，在目标数据库执行以下脚本：

1. [基础表](../sql/schema.sql)：用户、活动、报名、场馆。
2. [异步报名表](../sql/20260924_async_booking.sql)。
3. [候补表及迁移](../sql/20260928_waitlist_complete.sql)。

已有数据库先核对表结构，不要重跑所有建表脚本。新库的 schema 已包含活动分类和场馆，不必重复执行 `20260924_add_activity_category.sql`；`20260928_add_venue.sql` 用于补齐较旧数据库。脚本并非全部可重复执行。

## 应用配置

配置来源：[基础配置](../src/main/resources/application.properties)、[异步配置](../src/main/resources/application-async.properties)。连接信息可通过 IDEA 环境变量覆盖：

```text
SPRING_DATASOURCE_URL=jdbc:mysql://192.168.100.128:3306/campus_ticket
SPRING_DATASOURCE_USERNAME=你的数据库用户
SPRING_DATASOURCE_PASSWORD=你的数据库密码
SPRING_DATA_REDIS_HOST=192.168.100.128
SPRING_DATA_REDIS_PORT=6379
KAFKA_BOOTSTRAP_SERVERS=192.168.100.128:9092
SPRING_PROFILES_ACTIVE=async
```

Redis 配置了密码时再设置 `SPRING_DATA_REDIS_PASSWORD`。URL 的数据库名称必须与执行 SQL 的数据库一致；应用运行在 Windows 时，`localhost` 指 Windows 本机，不是虚拟机。

在 IDEA 运行 `CampusTicketApplication`。或者：

```powershell
mvn '-Dmaven.compiler.proc=full' spring-boot:run '-Dspring-boot.run.profiles=async'
```

默认端口 8081；多实例在第二个启动配置中设置 `SERVER_PORT=8082`。`kafka-demo` 仅用于消息收发演示，完整报名使用 `async`。关闭后台任务会影响补投、同步与候补恢复，不能仅凭 HTTP 接口启动成功判断系统完整运行。

## 首次使用

1. `GET /health` 确认应用运行。
2. 调用 `POST /auth/register` 注册专用管理员账号。
3. 数据库维护者执行以下语句，并确认只影响该账号：

```sql
UPDATE campus_user SET role = 'ADMIN' WHERE student_no = '你的专用管理员学号';
```

4. 登录获得 Token，后续使用 `Authorization: Bearer <token>`。
5. 管理员创建活动，再调用 `POST /activities/{id}/publish-with-inventory`。
6. 注册普通用户并按 [演示流程](demo.md) 验证报名。

仅调用 `/publish` 不会初始化 Redis 库存。`publish-with-inventory` 的发布事务与 Redis 初始化不是一个原子事务；若返回 `ACTIVITY_PUBLISHED_INVENTORY_NOT_READY`，先检查活动状态和 Redis，再用 `/booking-inventory` 补初始化，不要重新创建活动。库存已存在时初始化接口拒绝覆盖。

## 关键运行参数

| 参数 | 当前配置/意义 |
| --- | --- |
| `campus.rate-limit.booking-user.window-seconds` | 30 秒，用户滑动窗口；次数限制见相邻配置 |
| `campus.waitlist.confirm-seconds` | 300 秒；实际截止时间还受报名结束和活动开始时间限制 |
| `campus.booking.immediate-dispatch-enabled` | 立即投递开关，定时补投仍有作用 |
| `campus.booking.redis-sync-enabled` | 订单终态 Redis 补偿 |
| `campus.waitlist.jobs-enabled` | 候补扫描与延迟队列处理 |

普通报名 Redis 申请的处理期限当前由 `RedisConstants.BOOKING_PROCESSING_TIMEOUT` 控制，为 2 分钟；不要只修改配置中的 `order-timeout-seconds` 就认为已生效。活动与场馆 L1 当前 TTL 为 2 秒。完整开关和默认值以代码、配置文件为准。

## 常见排查

- 端口占用：修改 `server.port` 或停止确认无用的旧实例。
- 有连接但查不到数据：核对主机、库名、事务是否提交，以及活动是否已发布。
- 401：检查 Bearer 前缀、Token 是否失效；修改密码会使旧登录凭证失效。
- Kafka 连接失败：检查 broker 返回的 advertised 地址是否能从 Windows 访问。
- 订单长期 PENDING：检查消费者、Kafka、补投任务、数据库异常和申请期限，不要直接修改库存绕过状态机。

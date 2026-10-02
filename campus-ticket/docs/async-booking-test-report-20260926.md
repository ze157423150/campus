# 异步报名集成测试记录

> 历史证据：以下为 2026-09-26 当时版本的结果，未在本次文档整理中重跑。当前接口与功能以 [API 清单](backend-api.md)、[测试说明](testing.md) 为准。

时间：2026-09-26 17:00（Asia/Shanghai）。运行编号：`6ec2d8c3e2ca`。

## 环境与范围

- 真实 MySQL、虚拟机 Redis、Kafka；临时 Spring Boot HTTP 实例端口 58160。
- 独立 Kafka 主题 `campus.it.6ec2d8c3e2ca` 和独立消费组，使用项目真实 Kafka 消费者。
- 创建 1 个测试管理员、20 个学生、6 个活动（本轮活动 ID 40–45）。学生通过管理员接口创建，登录、活动创建发布、报名、查询和普通取消均走真实 HTTP 接口。
- 调用生产投递任务、补偿任务类，发现订单范围限制为本轮测试活动。为缩短测试等待，测试调度器分别每轮结束后等待 100ms、200ms 再运行；不是生产默认调度频率的性能测试。
- 数据库事务、条件领取 UPDATE、Redis Lua、Kafka 发送和消费均使用真实依赖；仅限制活动/待同步订单的发现范围。

## 结果

| 场景 | 实测结果 |
|---|---|
| 20个不同用户并发抢5个名额 | 5个202、15个409名额不足；最终5条有效报名，MySQL和Redis剩余名额均为0 |
| 同一用户20个并发请求 | 1个202、19个409重复报名；只产生1条成功订单和报名，两边名额各减少1 |
| 成功订单额外投递3次 | 等待对应Kafka消费位点提交后，仍只有1条有效报名、1条成功日志，名额未再次减少 |
| 查询归属检查 | 本人查询200，其他用户查询404 |
| 重复取消、重新报名、旧消息晚到 | 重复取消不多退名额；新订单报名成功后，再取消旧订单或投递旧消息，不影响新报名和新用户占用标记 |
| 数据库取消已提交、未执行Redis同步 | 只执行数据库取消后停止前台处理，由后台任务完成Redis取消并清除dirty，无需重发HTTP取消 |
| Redis取消已完成、dirty尚未清除 | 后台安全重放Lua并清除dirty，名额只归还一次 |

JUnit：6项集成测试全部通过，Failures=0，Errors=0，Skipped=0。测试类运行13.35秒，Maven总耗时17.372秒；这些时间不是吞吐量或接口延迟指标。

## 清理与限制

本轮创建的6个活动、21个账号、报名/订单/日志、登录Token、对应Redis数据和独立Kafka测试主题已清理；没有重置现有业务数据。已分配的数据库自增ID不会回退。

中断场景通过停在数据库提交后、或停在Redis执行后保留真实中间状态来验证恢复，没有杀进程、停止真实Redis/Kafka或重启虚拟机。本轮也没有进行多应用实例压测、大规模持续负载测试或Redis数据丢失测试。

## 复跑

测试类：`src/test/java/com/campus/ticket/AsyncBookingIntegrationTest.java`，默认禁用，只在显式启用后访问真实依赖。

```powershell
$env:CAMPUS_ASYNC_INTEGRATION_TESTS = 'true'
mvn -o '-Dmaven.compiler.proc=full' '-Dtest=AsyncBookingIntegrationTest' test
Remove-Item Env:CAMPUS_ASYNC_INTEGRATION_TESTS
```

需要项目配置的数据库、Redis和Kafka均可连接，并已创建项目所需表。

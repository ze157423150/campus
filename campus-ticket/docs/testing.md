# 测试与结果说明

## 已有验证证据

| 日期 | 范围 | 结果与限制 |
| --- | --- | --- |
| 2026-09-26 | 异步报名真实 Kafka 功能 | 见原始报告；包含并发争抢与重复消息，不代表当前全部模块回归 |
| 2026-09-27 | 定时投递与立即投递性能对比 | 两份原始 JSON；客户端与服务端同 JVM，不能解释为生产 QPS |
| 2026-09-28 | 场馆功能及相关回归 | 历史合计 42 项通过，见场馆文档 |
| 2026-09-30 | 自动候补与候补流程 | BookingSubmissionServiceTest 7 项、WaitlistIntegrationTest 14 项，总计 21 项通过，无失败、错误、跳过 |

最近一轮的本地日志为 `target/auto-waitlist-tests.log`，完成时间 2026-09-30 19:51:25，耗时 25.816 秒，测试标识 8f494997，日志记录已清理测试数据。target 是构建产物，可能被 clean 删除；本文件保留摘要，不把它当作永久归档。

候补集成测试使用真实 HTTP、MySQL、Redis 和 Redisson，但通过 Mockito 替换 BookingDispatchService，隔离 Kafka 投递。部分成功订单由真实消费 Service 显式调用完成。它验证业务状态流转，不证明真实 Kafka 全链路或高并发生产稳定性。

自动候补覆盖：有名额返回 BOOKING、满额自动 WAITLIST、重复请求只创建一个有效候补、不选择自动候补时仍返回满额、取消后邀请并确认，以及 8 个并发重复候补请求。其他候补测试覆盖 FIFO、任务重放、超时与数据库扫描、确认/拒绝竞争、同步失败恢复和事务回滚。

本次 2026-10-02 文档整理未重新运行业务测试或压测。

## 如何复跑

先用独立开发/测试数据库及中间件，按 [启动说明](setup.md) 初始化。测试会创建并清理自身数据，强制退出可能留下数据，自增 ID 不会回退。不要对含重要数据的环境随意执行故障注入。

单元测试：

```powershell
mvn '-Dmaven.compiler.proc=full' '-Dtest=BookingSubmissionServiceTest' test
```

候补集成测试：

```powershell
$env:CAMPUS_WAITLIST_INTEGRATION_TESTS = 'true'
try {
    mvn '-Dmaven.compiler.proc=full' '-Dtest=BookingSubmissionServiceTest,WaitlistIntegrationTest' test
} finally {
    Remove-Item Env:CAMPUS_WAITLIST_INTEGRATION_TESTS -ErrorAction SilentlyContinue
}
```

其他可选测试分别使用以下开关；用对应 `-Dtest=类名` 限定范围，完成后清除环境变量：

| 测试类 | 环境变量 |
| --- | --- |
| AccountAndRegistrationIntegrationTest | CAMPUS_INTEGRATION_TESTS=true |
| VenueIntegrationTest | CAMPUS_VENUE_INTEGRATION_TESTS=true |
| AsyncBookingIntegrationTest | CAMPUS_ASYNC_INTEGRATION_TESTS=true |
| 异步压力场景 | 另需 CAMPUS_ASYNC_STRESS_TESTS=true；参数以测试源码为准 |

未开启可选集成测试时的 BUILD SUCCESS 可能包含大量跳过，必须同时看 Tests run、Skipped、Failures、Errors。上述命令是复跑入口，不是声称当前所有历史测试已再次通过。

## JMeter 验收口径

先确认活动时间、发布状态、Redis 初始化和用户 Token。每轮使用新活动，避免上一轮订单与候补影响结果。明确此次是测试限流，还是在受控测试配置下观察报名吞吐；429 应单独统计，不能混作抢票失败。

200 个用户各请求 5 次抢 50 个名额时：

1. 关闭自动候补测试普通链路，按用户提供不同 Token。
2. 同时记录 HTTP 202、409、429、5xx、超时等数量与响应耗时。
3. 等待异步处理结束，核对成功订单和有效报名均不超过 50，用户无重复有效报名，pending 收敛，Redis 与数据库库存无负数。
4. 分别报告 HTTP P95/P99、订单完成耗时、整批清空时间；不能把 HTTP 返回 202 的速度当作落库速度。
5. 自动候补另建场景，开启 joinWaitlistIfFull，统计 WAITLIST 数量及有效唯一性，再验证取消、邀请、确认与超时。

压测报告记录代码版本、线程数、循环次数、升压时间、名额、限流参数、投递参数、中间件资源和原始结果。若缺少这些条件，不应直接对比两次 QPS。

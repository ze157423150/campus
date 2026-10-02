# 项目文档导航

本目录以当前代码为准描述功能；带日期的测试报告记录当时版本，不覆盖后续变更。

| 阅读目的 | 文档 |
| --- | --- |
| 安装与启动 | [环境与启动](setup.md) |
| 查所有接口 | [API 清单](backend-api.md) |
| 注册、登录、修改密码 | [账号接口](account-registration-api.md) |
| 活动筛选与分页 | [活动搜索](activity-search-api.md) |
| 异步报名和候补请求 | [报名与候补 API](booking-api.md) |
| 了解整体代码 | [架构与代码导航](architecture.md) |
| 查表、状态、Redis 数据 | [数据与状态字典](data-model.md) |
| 取消、确认与超时递补 | [候补流程详解](waitlist-flow-guide.md) |
| Redis 同步和故障恢复 | [同步补偿](booking-redis-recovery.md) |
| 场馆业务与缓存 | [场馆指南](venue-guide.md) |
| 运行测试和解释性能数字 | [测试说明](testing.md) |
| 演示项目 | [演示步骤](demo.md) |
| 判断简历描述是否准确 | [边界与待办](limitations.md) |

## 历史证据

- [2026-09-26 Kafka 功能报告](async-booking-test-report-20260926.md)
- [2026-09-27 定时投递性能报告](async-booking-load-test-report-20260927.md)
- [2026-09-27 立即投递对比](async-booking-immediate-load-report-20260927.md)
- 原始数据：[定时投递](async-booking-load-results-20260927.json)、[立即投递](async-booking-immediate-load-results-20260927.json)

## 维护约定

新增或修改 Controller 时同时更新 API 清单和请求示例；修改状态机时更新数据字典与流程文档；测试结果注明日期、环境、是否使用真实中间件以及统计口径。历史结果保留，不改写为新版本测试结果。不要将真实密码、Token 或测试用户个人资料放入文档。

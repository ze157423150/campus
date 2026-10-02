# 完整 API 清单

默认地址 `http://localhost:8081`。以下按当前 Controller 整理，共 39 个路由（含 1 个仅演示 profile 可用的接口）。“登录”接口使用 `Authorization: Bearer <token>`；“管理员”要求 ADMIN。路径参数中的活动、订单和邀请必须匹配，个人接口还会校验归属。

成功响应没有统一外层包装，以接口实际返回对象为准。业务错误通常为 `{"code":"错误码","message":"说明"}`。常见 HTTP 状态：400 参数错误、401 未登录、403 权限不足、404 不存在、409 状态冲突、429 限流、503 暂时不可用。限流响应包含 `Retry-After`，客户端需要自行决定是否等待后重试。

## 账号

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| GET | `/health` | 公开 | 健康检查，文本响应 |
| POST | `/auth/register` | 公开 | 注册学生；201，userId |
| POST | `/auth/login` | 公开 | 登录；token |
| POST | `/auth/logout` | 登录 | 注销当前凭证 |
| GET | `/auth/me` | 登录 | 当前登录用户 |
| GET | `/users/me` | 登录 | 个人资料 |
| PUT | `/users/me` | 登录 | 修改姓名 |
| PUT | `/users/me/password` | 登录 | 修改密码 |
| POST | `/admin/users` | 管理员 | 创建学生；201，userId |

注册和创建用户：`{"studentNo":"test0001","name":"测试用户","password":"示例密码至少8位"}`。登录使用 studentNo、password；改密使用 oldPassword、newPassword。密码使用 BCrypt，不存明文。当前密码校验至少 8 个字符且不超过 72 UTF-8 字节，早期压测中的 `123456` 不适合作为当前注册示例。更多规则见 [账号说明](account-registration-api.md)。

## 活动

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| GET | `/activities` | 公开 | 已发布活动分页、筛选 |
| GET | `/activities/{id}` | 公开 | 已发布活动详情 |
| POST | `/activities` | 管理员 | 创建草稿；201，activityId |
| PUT | `/activities/{id}` | 管理员 | 修改草稿 |
| GET | `/activities/management` | 管理员 | 管理列表 |
| GET | `/activities/{id}/management` | 管理员 | 管理详情 |
| POST | `/activities/{id}/publish` | 管理员 | 仅发布数据库活动 |
| POST | `/activities/{activityId}/publish-with-inventory` | 管理员 | 发布并初始化 Redis |
| POST | `/activities/{id}/cancel` | 管理员 | 取消活动；异步链路限制见待办 |
| POST | `/activities/{activityId}/booking-inventory` | 管理员 | 初始化报名库存，拒绝覆盖已存在库存 |

创建/修改字段：title、category、location、startTime、endTime、registrationStartTime、registrationEndTime、totalQuota。分类为 LECTURE、SPORTS、CLUB、OTHER；日期示例 `2026-12-10T14:00:00`。时间需满足先报名后活动等校验，演示时自行调整。

公开列表：page 默认 1、pageSize 默认 10（最大 100），可传 keyword、category、registrationPhase。管理列表支持 page、pageSize、status。具体筛选见 [活动搜索](activity-search-api.md)。

## 报名与候补

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| POST | `/activities/{activityId}/registrations` | 登录 | 普通异步报名，可选满额自动候补；202 或 200 |
| GET | `/activities/{activityId}/booking-orders/{orderId}` | 本人 | 订单状态 |
| POST | `/activities/{activityId}/booking-orders/{orderId}/cancel` | 本人 | 取消已成功报名；200 或 202 |
| GET | `/users/me/registrations` | 登录 | 我的报名分页 |
| GET | `/activities/{activityId}/registrations` | 管理员 | 报名名单分页 |
| POST | `/activities/{activityId}/waitlist` | 登录 | 主动加入满额活动候补 |
| GET | `/activities/{activityId}/waitlist/me` | 本人 | 当前候补及邀请信息 |
| POST | `/activities/{activityId}/waitlist/{waitlistId}/cancel` | 本人 | 取消 WAITING 候补 |
| GET | `/waitlist-offers/{offerId}` | 本人 | 邀请详情 |
| POST | `/waitlist-offers/{offerId}/confirm` | 本人 | 确认邀请，获得正式订单 |
| POST | `/waitlist-offers/{offerId}/decline` | 本人 | 拒绝邀请 |
| GET | `/users/me/waitlist-notifications` | 登录 | 查询站内邀请通知 |
| POST | `/users/me/waitlist-notifications/{id}/read` | 本人 | 标记通知已读 |

报名请求可无 body，或传 `{"joinWaitlistIfFull":true}`。只有明确名额不足才自动候补，不能把 Redis 超时等未知结果当成满额。我的报名支持 page、pageSize、status（REGISTERED/CANCELLED）。通知支持 afterId（默认 0）、limit（默认 20、最大 100）。完整响应及操作顺序见 [报名 API](booking-api.md)。

旧的 `/activities/registrations/{registrationId}/cancel` 已不是当前取消入口；取消使用 orderId。

## 场馆

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| GET | `/venues` | 公开 | OPEN 场馆列表 |
| GET | `/venues/{id}` | 公开 | OPEN 场馆详情 |
| GET | `/venues/management` | 管理员 | 管理分页 |
| GET | `/venues/{id}/management` | 管理员 | 管理详情 |
| POST | `/venues` | 管理员 | 新建；201，venueId |
| PUT | `/venues/{id}` | 管理员 | 修改资料与状态 |

列表支持 page、pageSize、keyword；管理列表额外支持 status。写入字段：name、address、capacity、description、status（OPEN/CLOSED）。场馆目前是资料业务，不包含档期预约冲突检测，见 [场馆说明](venue-guide.md)。

## Kafka 学习接口

| 方法 | 路径 | 权限 | 用途 |
| --- | --- | --- | --- |
| POST | `/kafka-demo/messages` | 管理员 | 仅 kafka-demo profile；发送 key、message，返回 topic、partition、offset |

该接口不替代正式报名接口。正式异步链路使用 async profile。

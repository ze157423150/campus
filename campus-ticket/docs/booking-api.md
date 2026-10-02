# 报名与候补 API 使用说明

完整路由与权限见 [API 清单](backend-api.md)。所有示例 ID 均需替换，Token 来自登录响应。

## 普通报名与满额自动候补

```http
POST /activities/1/registrations
Authorization: Bearer <token>
Content-Type: application/json

{"joinWaitlistIfFull":true}
```

有名额时返回 HTTP 202，核心字段：

```json
{"type":"BOOKING","orderId":"订单ID","status":"PENDING"}
```

满额且允许候补时返回 HTTP 200，核心字段：

```json
{"type":"WAITLIST","waitlistId":1,"status":"WAITING"}
```

未使用的字段是否输出 null 取决于对象序列化配置，上述只展示有效字段。重复加入有效候补会复用已有记录，status 应读取实际响应。无 body 或 joinWaitlistIfFull=false 时，满额仍返回 409。报名服务只在 QUOTA_EXHAUSTED 时尝试候补；候补期间发现公开名额恢复会有限重试普通报名，持续竞争可能返回 BOOKING_STATE_CHANGED。

## 等待落库

`GET /activities/1/booking-orders/{orderId}` 返回 orderId、status、registrationId、failureCode。PENDING 需继续查询；SUCCEEDED 才表示正式报名完成；FAILED 查看 failureCode；CANCELLED 表示已取消。Kafka 投递成功日志不能代替订单查询结果。

`GET /users/me/registrations` 查询正式报名记录，因此刚收到 202 时可能还查不到记录。

## 取消正式报名

`POST /activities/1/booking-orders/{orderId}/cancel` 返回 orderId、status、redisSyncStatus、message。数据库已提交但 Redis 尚待同步时可能返回 202。重复请求和后台补偿共同保证收敛，不要求用户不断重试才能完成。

取消后释放的名额优先交给候补协调器，不是直接把所有公开库存加一。有有效候补时生成邀请，无人可递补时才回到公开库存。活动开始后等不允许取消的情况返回业务错误。

## 候补用户收到邀请

1. `GET /activities/1/waitlist/me` 查看 waitlistId、status、waitingAhead、offerId、offerStatus、confirmDeadline。
2. `GET /users/me/waitlist-notifications?afterId=0&limit=20` 读取站内通知。
3. `GET /waitlist-offers/{offerId}` 查询邀请详情。
4. 在截止前 `POST /waitlist-offers/{offerId}/confirm`，成功返回 orderId、SUCCEEDED 状态和提示。
5. 不参加则调用 `/decline`；仅处于 WAITING 时用候补记录 `/cancel`。

确认期限是配置时长与报名截止、活动开始时间的最早值，不保证总有完整 5 分钟。超时或活动关闭会释放预占，继续递补或回流。读到历史通知不代表邀请仍有效，确认时后端会重新校验。

目前通知是数据库站内消息，需要客户端主动查询；未实现短信、邮件、WebSocket 推送。

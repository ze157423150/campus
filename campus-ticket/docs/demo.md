# 不依赖付费工具的演示流程

使用 Apifox、Postman 或 Windows PowerShell 均可。先按 [启动说明](setup.md) 启用 async，并准备管理员与两个学生 A、B。所有账号密码均使用自己的测试值。

## PowerShell 调用基础

```powershell
$baseUrl = 'http://localhost:8081'
$loginBody = @{ studentNo = 'test0001'; password = 'CampusTest123!' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.token)" }
Invoke-RestMethod -Method Get -Uri "$baseUrl/users/me" -Headers $headers
```

每一段完整复制执行，不用 Bash 的反斜杠换行。出现 `>>` 通常是引号或括号尚未闭合，可 Ctrl+C 后重新输入；401 时检查实际 Token，不要把 `<token>` 占位符直接发送。

## 普通报名演示

1. 管理员 `POST /activities` 创建总名额为 1 的活动；报名开始时间早于现在，结束时间与活动时间在未来。
2. 调用 `/activities/{activityId}/publish-with-inventory`，记录活动 ID。
3. A 调用 `/activities/{activityId}/registrations`，记录返回 orderId。
4. 查询 `/activities/{activityId}/booking-orders/{orderId}`，等到 SUCCEEDED，再查看我的报名。
5. 再次报名验证重复拦截。重复请求可能受限流影响，区分 409 与 429。

请求代码：

```powershell
$activityId = 1 # 替换为本轮新活动
$result = Invoke-RestMethod -Method Post -Uri "$baseUrl/activities/$activityId/registrations" -Headers $headers -ContentType 'application/json' -Body '{"joinWaitlistIfFull":true}'
$result
if ($result.type -eq 'BOOKING') {
    Invoke-RestMethod -Method Get -Uri "$baseUrl/activities/$activityId/booking-orders/$($result.orderId)" -Headers $headers
}
```

## 候补递补演示

1. B 用自己的 Token 提交 joinWaitlistIfFull=true，应得到 WAITLIST。
2. A 用自己的 Token 取消成功订单。
3. B 查询 `/activities/{activityId}/waitlist/me` 或站内通知，等 offerStatus 为 OFFERED，记录 offerId 与截止时间。
4. B 在截止前确认；查询返回的正式订单与我的报名。
5. 用另一个新活动再测超时：加入 B、C 两名候补，A 取消，B 不确认；到期后 C 得到邀请。无人候补时验证名额回到公开库存。

测试超时可以在独立测试环境缩短 confirm-seconds 并重启；已激活邀请使用已保存的截止时间，不会因修改配置自动改变。

## 缓存与限流演示

连续查询同一已发布活动详情，结合 SQL 日志验证命中；超过逻辑有效期后观察先返回与后台重建。不要将管理员管理详情当作公开缓存接口测试。

快速重复提交报名观察 429 和 Retry-After；令牌桶、用户窗口、IP 限制分别设置可区分的测试参数，避免同时触发后无法判断是哪层限流。

演示结束保留结果截图或响应记录，隐藏 Token。不要通过直接清空全库或 Redis 来重置测试，应使用独立活动与账号。

# 基础后端接口

默认地址 `http://localhost:8081`。需登录接口携带 `Authorization: Bearer <token>`。公开接口不需要该请求头；若主动传入失效Token，也会被认证过滤器拒绝。

| 方法 | 路径 | 权限 | 请求/返回 |
| --- | --- | --- | --- |
| GET | /health | 公开 | 返回运行字符串，只验证进程可响应 |
| POST | /auth/register | 公开 | studentNo/name/password → 201 userId |
| POST | /auth/login | 公开 | studentNo/password → token |
| POST | /auth/logout | 登录 | 当前Token注销，返回message |
| GET | /auth/me | 登录 | 当前用户id/studentNo/name/role |
| GET | /users/me | 登录 | 个人资料 |
| PUT | /users/me | 登录 | name → 更新后资料 |
| PUT | /users/me/password | 登录 | oldPassword/newPassword → message；旧会话失效 |
| GET | /activities | 公开 | page/pageSize/keyword/category/registrationPhase → 分页 |
| GET | /activities/{id} | 公开 | 已发布活动详情；未发布/取消/不存在返回404 |
| POST | /activities | 管理员 | 活动请求体 → 201 activityId |
| PUT | /activities/{id} | 管理员 | 完整活动请求体 → message，仅可编辑草稿 |
| GET | /activities/management | 管理员 | page/pageSize/status → 分页，包含草稿和取消活动 |
| GET | /activities/{id}/management | 管理员 | 任意状态的活动详情 |
| POST | /activities/{id}/publish | 管理员 | 无正文 → message |
| POST | /activities/{id}/cancel | 管理员 | 无正文 → message，同时取消有效报名、归还全部名额 |
| POST | /activities/{activityId}/registrations | 登录 | 无正文 → registrationId，用户身份取自Token |
| POST | /activities/registrations/{registrationId}/cancel | 登录 | 无正文 → message，仅限本人 |
| GET | /users/me/registrations | 登录 | page/pageSize/status → 本人的报名分页 |
| GET | /activities/{activityId}/registrations | 管理员 | page/pageSize/status → 活动报名名单分页 |

分页默认page=1、pageSize=10；page>=1、1<=pageSize<=100。返回 `{ "total": 0, "page": 1, "pageSize": 10, "records": [] }`。管理活动status为DRAFT/PUBLISHED/CANCELLED，报名status为REGISTERED/CANCELLED；不传查询全部。

## 创建和编辑活动

```json
{
  "title": "校园篮球赛",
  "category": "SPORTS",
  "location": "体育馆",
  "startTime": "2026-10-10T14:00:00",
  "endTime": "2026-10-10T17:00:00",
  "registrationStartTime": "2026-09-24T08:00:00",
  "registrationEndTime": "2026-10-09T18:00:00",
  "totalQuota": 100
}
```

标题与地点非空，长度上限分别100和200；totalQuota为正数。活动开始必须晚于当前时间，结束晚于开始；报名结束晚于报名开始及当前时间，且不得晚于活动开始。分类见搜索文档，省略时为OTHER。发布前仍会检查时间是否已经过期。

## 报名和取消规则

- 活动已发布且 `报名开始时间 <= 当前时间 < 报名结束时间` 才能报名；剩余名额必须大于0。
- 同一用户同一活动最多一条报名记录，重复有效报名返回409；取消后重新报名需要重新抢名额。
- 取消报名仅允许活动开始前，重复取消返回成功且不重复归还名额。
- 管理员取消已发布活动必须在活动开始前；取消草稿不受此限制。重复取消活动返回成功。
- 活动详情剩余名额有短时缓存，最终是否报名成功以报名接口为准。

## 错误响应

业务及已处理的参数错误返回JSON，例如 `{"code":"QUOTA_EXHAUSTED","message":"活动名额已满"}`。400表示参数不合法；401未登录/凭证失效；403非管理员；404不存在或不属于本人；409业务状态冲突。未知框架错误不保证具有同一响应结构。

常用409代码：DUPLICATE_REGISTRATION、QUOTA_EXHAUSTED、REGISTRATION_NOT_STARTED、REGISTRATION_CLOSED、ACTIVITY_NOT_PUBLISHED、INVALID_ACTIVITY_STATUS、CANCELLATION_CLOSED。账号参数与错误示例见账号文档。

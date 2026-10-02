# 用户与报名接口

基础地址：`http://localhost:8081`。JSON 请求使用 `Content-Type: application/json`。
需要登录的接口使用 `Authorization: Bearer <token>`。

## 功能与兼容说明

- 新增学生注册、查询/修改个人资料、修改密码。
- 新增管理员分页查询活动报名名单。
- 原 `GET /users/me/registrations` 从数组改为分页对象，路径不变。
- Redis 登录值改为 `用户ID:密码哈希指纹`。BCrypt 仍负责保存和校验密码；SHA-256 仅用于生成会话凭据指纹，不代替 BCrypt。
- 改密后所有旧 Token 在下一次请求时失效；Redis 旧键保留到原 TTL 到期，不扫描所有登录键。
- 升级前仅保存用户 ID 的 Token 不再接受，重启项目后需要重新登录。无需修改数据库表结构。
- 注册仅适用于当前教学项目的自助建号，尚未验证学号归属，不代表校园统一身份认证。

## 接口清单

| 方法 | 路径 | 权限 | 成功状态 |
|---|---|---|---|
| POST | `/auth/register` | 公开 | 201 |
| POST | `/auth/login` | 公开 | 200 |
| GET | `/users/me` | 登录用户 | 200 |
| PUT | `/users/me` | 登录用户 | 200 |
| PUT | `/users/me/password` | 登录用户 | 200 |
| GET | `/users/me/registrations` | 登录用户 | 200 |
| GET | `/activities/{activityId}/registrations` | ADMIN | 200 |

`/auth/me`、`/auth/logout` 同样可用；管理员创建学生使用 `POST /admin/users`。当前报名及取消使用异步订单接口，详见 [完整 API 清单](backend-api.md) 和 [报名说明](booking-api.md)，不要使用旧的按 registrationId 取消路径。

## 注册

```json
{"studentNo":"20260003","name":"王同学","password":"CampusTest123!"}
```

返回 `{"userId":3}`，不会自动登录。学号1～32位字母、数字、下划线或连字符；姓名去除首尾空格后不能为空且最多50个Java字符；新密码至少8个Java字符且UTF-8编码后最多72字节。密码保留首尾空格。角色在 INSERT 中固定为 STUDENT，提交 role、id、passwordHash 等字段不会用于赋值。重复学号返回409 `STUDENT_NO_EXISTS`。

## 个人资料

`GET /users/me` 返回 `id`、`studentNo`、`name`、`role`，不返回密码哈希。

`PUT /users/me` 请求：

```json
{"name":"新的姓名"}
```

当前仅允许修改姓名。学号、角色、用户ID不能通过资料接口修改。身份来自 Token，不接受外部指定用户 ID。

## 修改密码

`PUT /users/me/password` 请求：

```json
{"oldPassword":"CampusTest123!","newPassword":"NewCampusTest456!"}
```

成功返回 `{"message":"密码已修改，请重新登录"}`。原密码错误返回400 `OLD_PASSWORD_INCORRECT`；新密码与原密码相同或长度不合法返回400。事务提交后，包括当前 Token 在内的旧 Token 均不能通过下一次认证。客户端应清除 Token 并重新登录。已通过认证且正在执行的请求不会被追溯撤销。

## 我的报名分页

`GET /users/me/registrations?page=1&pageSize=10&status=REGISTERED`

`page` 默认1，必须>=1；`pageSize` 默认10，范围1～100。
`status` 可省略或为空白，表示查询所有报名状态；有效值为 REGISTERED、CANCELLED，区分大小写。

```json
{
  "total":1,
  "page":1,
  "pageSize":10,
  "records":[{
    "registrationId":4,
    "activityId":9,
    "activityTitle":"活动标题",
    "activityStatus":"PUBLISHED",
    "location":"教学楼A101",
    "startTime":"2026-10-10T14:00:00",
    "registrationTime":"2026-09-24T12:00:00",
    "status":"REGISTERED",
    "cancelTime":null
  }]
}
```

按报名ID倒序。`registrationTime` 仍表示记录首次创建时间，恢复报名不改这个时间。
没有匹配记录时返回200、`total: 0` 和 `records: []`；超出末页时保留实际 total，records 为空。

## 管理员报名名单

`GET /activities/9/registrations?page=1&pageSize=10&status=CANCELLED`

分页与状态规则同上。支持查询草稿、已发布、已取消活动的名单；不存在的活动返回404。学生访问返回403，未登录返回401。此接口的 GET 与同路径的 POST 报名接口并存。

返回分页对象，records 中每项包含：

```json
{
  "registrationId":4,
  "userId":1,
  "studentNo":"20260001",
  "name":"张三",
  "status":"CANCELLED",
  "registrationTime":"2026-09-24T12:00:00",
  "cancelTime":"2026-09-24T12:10:00"
}
```

名单中的姓名是当前用户资料中的姓名，不是报名时的历史快照。

## 验证

`src/test/java/com/campus/ticket/AccountAndRegistrationIntegrationTest.java` 是显式启用的集成测试，会启动随机端口，连接当前配置的 MySQL/Redis，创建唯一标识的用户和活动，并在每个测试结束后仅清理这些记录和对应 Token。不会重置自增ID；若进程被强制终止，清理无法保证完成。

在项目目录的 PowerShell 中运行：

```powershell
$env:CAMPUS_INTEGRATION_TESTS = 'true'
try {
    mvn '-Dmaven.compiler.proc=full' test
} finally {
    Remove-Item Env:CAMPUS_INTEGRATION_TESTS -ErrorAction SilentlyContinue
}
```

未设置环境变量时，该集成测试自动跳过，防止日常构建意外操作数据库。`maven.compiler.proc=full` 用于命令行JDK较新时显式启用当前项目的Lombok注解处理。

配套请求位于 `requests/account-registration.http`，也可以按文档在 Postman 中填写。

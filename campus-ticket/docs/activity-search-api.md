# 活动分类与搜索

旧数据库缺少 category 时执行 `sql/20260924_add_activity_category.sql`；新库的 schema.sql 已包含该字段，不要重复迁移。完整初始化步骤见 [环境与启动](setup.md)，接口总览见 [API 清单](backend-api.md)。

`GET /activities` 无须登录，支持以下可选参数，条件之间为“并且”：

| 参数 | 含义 |
| --- | --- |
| keyword | 标题包含该文字，去除首尾空白，最多100字符；`%` 和 `_` 按普通文字匹配 |
| category | `LECTURE` 讲座、`SPORTS` 文体赛事、`CLUB` 社团活动、`OTHER` 其他 |
| registrationPhase | `NOT_STARTED` 未开始、`OPEN` 报名中、`CLOSED` 已截止 |
| page / pageSize | 默认1 / 10；page至少1，pageSize为1到100 |

不传或只传空白表示不筛选；枚举值使用大写，非法参数返回400。

示例：`http://localhost:8081/activities?keyword=篮球&category=SPORTS&registrationPhase=OPEN&page=1&pageSize=10`

返回仍为 `{ "total": 0, "page": 1, "pageSize": 10, "records": [] }`，活动对象新增 `category`。total为筛选后的总数，超出末页时records为空。只返回已发布活动，按id倒序排列。

报名阶段按服务器当前时间计算：未开始为当前时间早于报名开始时间；报名中为开始时间已到且截止时间未到；已截止为截止时间已到。名额为0的活动也可能处于报名中，报名阶段不代表剩余名额。

管理员 `POST /activities` 和 `PUT /activities/{id}` 的原请求体增加 `"category": "SPORTS"`。不填或空白默认为OTHER，兼容原请求；PUT是完整编辑，省略分类会改为OTHER。旧活动统一为OTHER，草稿可通过编辑接口调整。管理列表、管理详情和公开详情均返回分类。

当前活动详情缓存前缀为 `campus:activity:published:detail:v3:`，统一定义在 RedisConstants 中。列表筛选与详情缓存是不同查询路径，不能因详情命中缓存就认为搜索列表也不访问数据库。

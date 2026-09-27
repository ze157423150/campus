# 取消及超时订单的 Redis 自动同步

## 流程

1. 取消事务更新报名、归还 MySQL 名额，将订单设为 `CANCELLED`、`redis_dirty=1`、`next_check_time=NOW(3)`。
2. 数据库提交后，接口立即调用 `BookingRedisSyncService.synchronize(orderId)`。
3. Lua 同步成功再清除 `redis_dirty`。正常响应为 HTTP 200，`redisSyncStatus=COMPLETED`。
4. 立即同步异常时，返回 HTTP 202，订单仍为 `CANCELLED`，`redisSyncStatus=PENDING`。用户不必再次取消。
5. `BookingRedisSyncJob` 定期查询已到重试时间的脏终态订单，用条件 UPDATE 领取，再同步 Redis。
6. 失败保持脏标记；领取前已推迟重试时间，即使进程停止，恢复后仍可再次领取。

后台只同步 Redis，不再次执行 MySQL 取消或归还数据库名额。`FAILED/EXPIRED` 订单也复用已有 Lua 补偿。

## 配置

激活 `async` profile 后启用，默认每轮结束 5 秒后再扫描，每轮最多 50 条，领取后至少等待 30 秒才可重新领取。

```properties
campus.booking.redis-sync-enabled=true
campus.booking.redis-sync-delay-ms=5000
campus.booking.redis-sync-initial-delay-ms=5000
campus.booking.redis-sync-batch-size=50
campus.booking.redis-sync-retry-seconds=30
```

这个开关独立于 Kafka 投递开关 `campus.booking.jobs-enabled`。无需新增表或字段，复用 `redis_dirty`、`next_check_time` 和已有索引。

## 并发与恢复

- 多实例可能查询到相同订单，但条件 UPDATE 只有一个实例能成功；过了重试时间才允许再次领取。
- 领取时间是临时占用，不是严格的全程互斥。接口、消费者、后台任务仍可能重叠执行，最终由已有 Lua 保证重复补偿不多退名额。
- Redis 成功、MySQL 清除标记失败时，标记仍在，重复执行 Lua 后再清除即可。
- 已清除标记的订单由 Service 跳过。
- 单条订单失败不会阻止同批其他订单处理。
- Redis 数据缺失或状态异常时，任务保留标记并输出异常，不凭空恢复库存。此类问题仍需核对数据；本功能不是全量对账或 Redis 数据灾难恢复。

## 人工验证

针对一个独立测试活动，正常报名成功后，在 `BookingCancellationController` 中调用 `cancelInDatabase` 的下一行打断点（暂停当前请求线程），此时数据库事务已提交。

1. 观察订单已为 `CANCELLED`，数据库名额归还一次，`redis_dirty=1`。
2. 不继续该 HTTP 请求，保持后台线程运行。
3. 等待后台扫描，确认日志出现“订单Redis后台同步完成”、Redis 状态变为 `CANCELLED`、名额仅归还一次、`redis_dirty=0`。
4. 恢复请求线程，重复同步应跳过或幂等完成，不再次归还名额。

若需要验证重启恢复，可在同一断点位置停止实例，随后重启 `async` 实例。只要没有其他实例已处理，该持久化脏标记会被重新扫描；不需要重发取消请求。

```sql
SELECT order_id, status, redis_dirty, next_check_time
FROM booking_order
WHERE order_id = '实际订单编号';
```

定时频率不等于完成时限；批量积压、存储故障、调度延迟都会影响实际恢复时间。

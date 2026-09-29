package com.campus.ticket.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@Profile("async")
@RequiredArgsConstructor
public class BookingConsumer
{
    private final JsonMapper jsonMapper;
    private final BookingConsumeService bookingConsumeService;
    private final BookingRedisStore bookingRedisStore;
    private final BookingMapper bookingMapper;
    private final com.campus.ticket.service.BookingRedisSyncService bookingRedisSyncService;

    @KafkaListener(
            topics = "${campus.booking.topic}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, String> record)
    {
        BookingMessage message = jsonMapper.readValue(
                record.value(),
                BookingMessage.class
        );

        if (message == null
                || message.orderId() == null
                || !message.orderId().equals(record.key()))
        {
            throw new IllegalArgumentException("Kafka消息key与订单编号不一致");
        }

        // 调用另一个Spring Bean，正常返回时数据库事务已经提交
        Long registrationId;

        try
        {
            BookingConsumeResult result = bookingConsumeService.consume(message);

            if (BookingOrderStatus.SUCCEEDED.equals(result.status()))
            {
                boolean written = bookingRedisStore.markSucceeded(message, result.registrationId());

                if (written)
                {
                    log.info(
                            "异步报名处理成功，orderId={}, registrationId={}",
                            message.orderId(),
                            result.registrationId()
                    );
                }
                else
                {
                    log.info(
                            "订单已取消，跳过旧成功结果回写，orderId={}",
                            message.orderId()
                    );
                }
            }
            else if (BookingOrderStatus.FAILED.equals(result.status()))
            {
                bookingRedisStore.markFailed(message, result.failureCode());

                // Redis补偿成功后，再清除待补偿标记
                bookingMapper.clean(message.orderId());

                log.info(
                        "报名失败补偿完成，orderId={}, failureCode={}",
                        message.orderId(),
                        result.failureCode()
                );
            }else if (BookingOrderStatus.CANCELLED.equals(result.status()))
            {
                bookingRedisSyncService.synchronize(message.orderId());

                log.info(
                        "已取消订单同步完成，orderId={}",
                        message.orderId()
                );
            }
            else
            {
                throw new IllegalStateException("不支持的消费结果状态");
            }
        }
        catch (RuntimeException e)
        {
            log.error(
                    "报名消费处理未完成，orderId={}, partition={}, offset={}",
                    message.orderId(),
                    record.partition(),
                    record.offset(),
                    e
            );

            throw e;
        }
    }
}

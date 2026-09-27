package com.campus.ticket.service;

import com.campus.ticket.booking.BookingDispatchMessage;
import com.campus.ticket.booking.BookingRedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookingDispatchService
{
    private final BookingRedisStore bookingRedisStore;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${campus.booking.topic:}")
    private String topic;

    @Value("${campus.booking.immediate-dispatch-enabled:false}")
    private boolean immediateDispatchEnabled;

    public void dispatchImmediately(Long activityId, String orderId)
    {
        if (!immediateDispatchEnabled)
        {
            return;
        }

        try
        {
            BookingDispatchMessage message = bookingRedisStore.claimRequest(activityId, orderId);
            if (message != null)
            {
                send(activityId, message);
            }
        }
        catch (RuntimeException e)
        {
            // Redis 已受理，立即领取失败不能把整个报名改判为失败。
            log.warn("立即投递未完成，保留申请等待补偿，activityId={}, orderId={}", activityId, orderId, e);
        }
    }

    public void send(Long activityId, BookingDispatchMessage message)
    {
        if (message.requestJson().isBlank())
        {
            log.error("待投递订单缺少申请记录，activityId={}, orderId={}", activityId, message.orderId());
            return;
        }

        try
        {
            kafkaTemplate.send(topic, message.orderId(), message.requestJson()).whenComplete((result, exception) -> {
                if (exception != null)
                {
                    log.warn("Kafka投递未确认，保留任务等待重试，activityId={}, orderId={}", activityId, message.orderId(), exception);
                    return;
                }

                // Kafka 确认不等于报名落库；由消费者在终态清理 pending。
                log.info("Kafka投递成功，orderId={}, partition={}, offset={}", message.orderId(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            });
        }
        catch (RuntimeException e)
        {
            // send() 也可能同步抛出异常，例如元数据等待超时。
            log.warn("Kafka发送调用失败，保留任务等待重试，activityId={}, orderId={}", activityId, message.orderId(), e);
        }
    }
}

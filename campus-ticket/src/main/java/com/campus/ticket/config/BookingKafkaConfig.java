package com.campus.ticket.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableScheduling
@Profile("async")
public class BookingKafkaConfig
{
    @Bean
    public NewTopic bookingTopic(
            @Value("${campus.booking.topic}") String topic)
    {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic bookingDeadLetterTopic(
            @Value("${campus.booking.dead-letter-topic}") String topic)
    {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public DefaultErrorHandler bookingErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${campus.booking.dead-letter-topic}") String deadLetterTopic)
    {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        deadLetterTopic,
                        record.partition()
                )
        );

        // 失败消息没有成功保存到Kafka时，不能当作已经处理完毕
        recoverer.setFailIfSendResultIsError(true);

        return new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(1000L, 2L)
        );
    }
}
package com.campus.ticket.messaging;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("kafka-demo")
public class KafkaDemoConsumer
{
    @KafkaListener(topics = "campus-java-demo")
    public void consume(ConsumerRecord<String, String> record)
    {
        log.info(
                "收到Kafka消息：topic={}，partition={}，offset={}，key={}，value={}",
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value()
        );
    }
}
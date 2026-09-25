package com.campus.ticket.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@Profile("kafka-demo")
public class KafkaDemoConfig
{
    @Bean
    public NewTopic kafkaDemoTopic()
    {
        return TopicBuilder.name("campus-java-demo")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
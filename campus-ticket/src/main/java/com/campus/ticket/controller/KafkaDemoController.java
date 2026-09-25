package com.campus.ticket.controller;


import com.campus.ticket.context.UserHolder;
import com.campus.ticket.exception.BusinessException;
import io.reactivex.rxjava3.internal.operators.observable.ObservableGenerate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@RestController
@Profile("kafka-demo")
@RequiredArgsConstructor
@RequestMapping("/kafka-demo")
public class KafkaDemoController {
    private final KafkaTemplate<String,String> kafkaTemplate;

    public record SendMessageRequest(String key, String message)
    {
    }
    @PostMapping("/messages")
    public Map<String, Object> send(@RequestBody SendMessageRequest request){
        UserHolder.requireAdmin();

        if(request.key() == null || request.key().isBlank()|| request.message() == null || request.message().isBlank()
                || request.key().length() > 100 || request.message().length() > 1000){
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ARGUMENT",
                    "key和message不能为空，长度上限分别为100和1000"
            );
        }


        try {
            SendResult<String, String> result = kafkaTemplate
                    .send("campus-java-demo", request.key(), request.message())
                    .get(10, TimeUnit.SECONDS);
            return Map.of(
                    "message", "Kafka已确认接收",
                    "topic", result.getRecordMetadata().topic(),
                    "partition", result.getRecordMetadata().partition(),
                    "offset", result.getRecordMetadata().offset()
            );



        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SEND_INTERRUPTED",
                    "等待发送结果时被中断，消息结果未确认",
                    e
            );
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            log.warn("Kafka测试消息发送未确认，key={}", request.key(), e);

            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SEND_UNCONFIRMED",
                    "消息发送未确认，请检查日志和消费者",
                    e
            );
        }
    }

}

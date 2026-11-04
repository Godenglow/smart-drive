package com.smartdrive.driver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.smartdrive.common.api.event.OrderCreatedEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.beans.factory.annotation.Qualifier;

@Component
public class OrderCreatedListener {
    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);
    private final ObjectMapper json;
    private final OrderMatchingService matching;
    private final RetryTemplate retry;

    public OrderCreatedListener(ObjectMapper json, OrderMatchingService matching,
            @Qualifier("matchingRetryTemplate") RetryTemplate retry) {
        this.json = json;
        this.matching = matching;
        this.retry = retry;
    }

    @RabbitListener(queues = "driver.matching.q", ackMode = "MANUAL")
    public void receive(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        OrderCreatedEvent event;
        try {
            event = json.readValue(message.getBody(), OrderCreatedEvent.class);
            OrderMatchingService.validate(event);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            log.warn("Matching dead-letter reason=invalid messageId={} queue=driver.matching.dlq",
                    message.getMessageProperties().getMessageId());
            channel.basicReject(tag, false);
            return;
        }
        boolean committed = retry.execute(context -> {
            if (context.getRetryCount() > 0) {
                log.warn("Matching retry eventId={} orderId={} attempt={} cause={}", event.eventId(),
                        event.orderId(), context.getRetryCount() + 1,
                        context.getLastThrowable().getClass().getSimpleName());
            }
            matching.match(event);
            return true;
        }, context -> {
            log.error("Matching dead-letter reason=exhausted eventId={} orderId={} attempts={} cause={} queue=driver.matching.dlq",
                    event.eventId(), event.orderId(), context.getRetryCount(),
                    context.getLastThrowable().getClass().getSimpleName());
            return false;
        });
        if (!committed) {
            channel.basicReject(tag, false);
            return;
        }
        // match() returns only after its MySQL transaction has committed. ACK failures can redeliver safely.
        // Settlement must remain OUTSIDE retry: a lost ACK must never retry a stale delivery tag.
        channel.basicAck(tag, false);
        log.info("Matching committed eventId={} orderId={} notificationDelayMs={} redelivered={}",
                event.eventId(), event.orderId(), Math.max(0, Duration.between(event.occurredAt(), Instant.now()).toMillis()),
                message.getMessageProperties().isRedelivered());
    }
}

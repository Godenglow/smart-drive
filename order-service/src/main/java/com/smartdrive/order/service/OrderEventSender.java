package com.smartdrive.order.service;

import com.smartdrive.order.config.RabbitTopology;
import com.smartdrive.order.entity.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrderEventSender {
    private final RabbitTemplate rabbit;
    private final long confirmTimeoutMs;

    public OrderEventSender(RabbitTemplate rabbit,
            @Value("${smartdrive.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        if (confirmTimeoutMs < 1) throw new IllegalArgumentException("Confirm timeout must be positive");
        this.rabbit = rabbit;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    public void send(OutboxEvent event) throws Exception {
        var message = MessageBuilder.withBody(event.payload.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.eventId).setType(event.eventType).build();
        // Each attempt needs its own correlation ID, including after a timeout.
        var correlation = new CorrelationData(event.eventId + ":" + UUID.randomUUID());
        rabbit.send(RabbitTopology.EVENTS, event.eventType, message, correlation);
        var confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        // RabbitMQ may ACK an unroutable message. A mandatory return must still fail.
        if (!confirm.isAck() || correlation.getReturned() != null) {
            throw new IllegalStateException(correlation.getReturned() != null
                    ? "Event returned as unroutable" : "Broker rejected event: " + confirm.getReason());
        }
    }
}

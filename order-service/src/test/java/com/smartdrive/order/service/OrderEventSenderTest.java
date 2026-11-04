package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartdrive.order.config.RabbitTopology;
import com.smartdrive.order.entity.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OrderEventSenderTest {
    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final OrderEventSender sender = new OrderEventSender(rabbit, 20);

    @Test void ackSendsPersistentJsonWithStableEventIdAndUniqueAttemptCorrelation() throws Exception {
        List<String> correlations = new ArrayList<>();
        var event = event();
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            assertEquals(event.eventId, message.getMessageProperties().getMessageId());
            assertEquals("order.created", message.getMessageProperties().getType());
            assertEquals("application/json", message.getMessageProperties().getContentType());
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            assertEquals(event.payload, new String(message.getBody(), StandardCharsets.UTF_8));
            correlations.add(correlation.getId());
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(eq(RabbitTopology.EVENTS), eq("order.created"), any(Message.class), any(CorrelationData.class));
        sender.send(event);
        sender.send(event);
        assertNotEquals(correlations.get(0), correlations.get(1));
    }

    @Test void nackIsNotSuccess() {
        complete(false, false);
        assertThrows(IllegalStateException.class, () -> sender.send(event()));
    }

    @Test void mandatoryReturnDespiteAckIsNotSuccess() {
        complete(true, true);
        assertThrows(IllegalStateException.class, () -> sender.send(event()));
    }

    @Test void absentOrLateConfirmTimesOut() {
        List<CorrelationData> pending = new ArrayList<>();
        doAnswer(call -> { pending.add(call.getArgument(3)); return null; })
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(TimeoutException.class, () -> sender.send(event()));
        // Completing after the timeout has no callback that can mark an outbox row SENT.
        assertTrue(pending.getFirst().getFuture().complete(new CorrelationData.Confirm(true, null)));
    }

    @Test void connectionFailureIsNotSuccess() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("test unavailable")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertThrows(AmqpConnectException.class, () -> sender.send(event()));
    }

    private void complete(boolean ack, boolean returned) {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            if (returned) correlation.setReturned(new ReturnedMessage(call.getArgument(2), 312,
                    "NO_ROUTE", RabbitTopology.EVENTS, "order.created"));
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "test nack"));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    static OutboxEvent event() {
        var event = new OutboxEvent();
        event.id = 12L;
        event.eventId = UUID.randomUUID().toString();
        event.aggregateId = 42;
        event.eventType = "order.created";
        event.payload = "{\"orderId\":42}";
        return event;
    }
}

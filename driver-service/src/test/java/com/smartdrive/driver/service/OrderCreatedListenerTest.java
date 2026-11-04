package com.smartdrive.driver.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.smartdrive.common.api.event.OrderCreatedEvent;
import com.smartdrive.driver.config.MatchingRetryConfiguration;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class OrderCreatedListenerTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final OrderMatchingService matching = mock(OrderMatchingService.class);
    final Channel channel = mock(Channel.class);
    final OrderCreatedListener listener = new OrderCreatedListener(json, matching,
            new MatchingRetryConfiguration().matchingRetryTemplate(4, 1, 4));

    static OrderCreatedEvent event() {
        return new OrderCreatedEvent(UUID.randomUUID().toString(), "order.created", 42,
                new BigDecimal("117.215"), new BigDecimal("39.136"), Instant.now());
    }

    Message message(byte[] body) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(7);
        return new Message(body, properties);
    }

    @Test void acknowledgesOnlyAfterCommittedMatchingReturns() throws Exception {
        var event = event();
        listener.receive(message(json.writeValueAsBytes(event)), channel);
        var order = inOrder(matching, channel);
        order.verify(matching).match(event);
        order.verify(channel).basicAck(7, false);
        verifyNoMoreInteractions(channel);
    }

    @Test void malformedAndInvalidMessagesAreRejectedWithoutRequeue() throws Exception {
        for (String body : new String[]{"not-json", "null", "{}", "{\"type\":\"other\"}"}) {
            listener.receive(message(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)), channel);
        }
        verify(channel, times(4)).basicReject(7, false);
        verifyNoInteractions(matching);
    }

    @Test void databaseOrRedisFailureExhaustsFourAttemptsThenRejectsWithoutAck() throws Exception {
        var event = event();
        doThrow(new IllegalStateException("database unavailable")).when(matching).match(event);
        listener.receive(message(json.writeValueAsBytes(event)), channel);
        verify(matching, times(4)).match(event);
        verify(channel).basicReject(7, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoMoreInteractions(channel);
    }

    @Test void transientFailureRetriesAndAcknowledgesOnceAfterRecovery() throws Exception {
        var event = event();
        doThrow(new IllegalStateException("Redis unavailable")).doNothing().when(matching).match(event);
        listener.receive(message(json.writeValueAsBytes(event)), channel);
        verify(matching, times(2)).match(event);
        verify(channel).basicAck(7, false);
        verifyNoMoreInteractions(channel);
    }

    @Test void ackFailurePropagatesWithoutSecondSettlement() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicAck(7, false);
        assertThrows(IOException.class, () -> listener.receive(message(json.writeValueAsBytes(event())), channel));
        verify(matching, times(1)).match(any());
        verify(channel).basicAck(7, false);
        verifyNoMoreInteractions(channel);
    }

    @Test void validatesIdentityTypeCoordinatesAndTimestamp() {
        var e = event();
        assertThrows(IllegalArgumentException.class, () -> OrderMatchingService.validate(
                new OrderCreatedEvent("bad", e.type(), e.orderId(), e.pickupLng(), e.pickupLat(), e.occurredAt())));
        assertThrows(IllegalArgumentException.class, () -> OrderMatchingService.validate(
                new OrderCreatedEvent(e.eventId(), e.type(), 0, e.pickupLng(), e.pickupLat(), e.occurredAt())));
        assertThrows(IllegalArgumentException.class, () -> OrderMatchingService.validate(
                new OrderCreatedEvent(e.eventId(), e.type(), e.orderId(), new BigDecimal("181"), e.pickupLat(), e.occurredAt())));
        assertThrows(IllegalArgumentException.class, () -> OrderMatchingService.validate(
                new OrderCreatedEvent(e.eventId(), e.type(), e.orderId(), e.pickupLng(), new BigDecimal("86"), e.occurredAt())));
        assertThrows(IllegalArgumentException.class, () -> OrderMatchingService.validate(
                new OrderCreatedEvent(e.eventId(), e.type(), e.orderId(), e.pickupLng(), e.pickupLat(), null)));
    }
}

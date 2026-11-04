package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartdrive.order.entity.OutboxEvent;
import com.smartdrive.order.mapper.OutboxMapper;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "SMARTDRIVE_MYSQL_INTEGRATION", matches = "true")
class OutboxMySqlIntegrationTest {
    @Test void backlogReportsPendingCountAndOldestAgeFromDatabase() throws Exception {
        try (var f = new OutboxTestFixture()) {
            long id = f.eventId(f.create().id());
            f.jdbc.update("UPDATE outbox_event SET created_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 10 SECOND) WHERE id=?", id);
            var backlog = f.outbox.backlog();
            assertTrue(backlog.pending >= 1);
            assertTrue(backlog.oldestAgeMs >= 10000);
        }
    }

    @Test void creationStoresOneWireEventAndIdempotentRetriesNeverAddAnother() throws Exception {
        try (var f = new OutboxTestFixture()) {
            var request = f.request();
            var order = f.service.create(f.passenger, request);
            var event = f.event(f.eventId(order.id()));
            var payload = f.json.readTree(event.payload);
            assertEquals("NEW", event.status);
            assertEquals(0, event.attempts);
            assertEquals(event.eventId, payload.get("eventId").asText());
            assertEquals("order.created", payload.get("type").asText());
            assertEquals(order.id(), payload.get("orderId").asLong());
            assertEquals(0, order.pickup().lng().compareTo(payload.get("pickupLng").decimalValue()));
            assertEquals(0, order.pickup().lat().compareTo(payload.get("pickupLat").decimalValue()));
            assertTrue(Instant.parse(payload.get("occurredAt").asText()).isBefore(Instant.now().plusSeconds(1)));
            reset(f.drivers);
            for (int i = 0; i < 5; i++) assertEquals(order.id(), f.service.create(f.passenger, request).id());
            verifyNoInteractions(f.drivers);
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", Integer.class, order.id()));
        }
    }

    @Test void failureAfterActualOutboxInsertRollsBackBothRowsAndSameRequestCanRetry() throws Exception {
        try (var f = new OutboxTestFixture()) {
            var faulty = mock(OutboxMapper.class);
            doAnswer(call -> {
                f.outbox.insert(call.getArgument(0));
                throw new IllegalStateException("Injected failure after outbox insert");
            }).when(faulty).insert(any());
            var creation = new OrderCreationTransaction(f.orders, faulty, f.json, f.manager);
            var service = new OrderService(f.orders, f.drivers, creation);
            var request = f.request();
            assertThrows(IllegalStateException.class, () -> service.create(f.passenger, request));
            assertNull(f.orders.findByRequest(f.passenger.userId(), request.requestId().toString()));
            // The failed insert's generated aggregate ID still exists in the captured parameter.
            var inserted = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
            verify(faulty).insert(inserted.capture());
            assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_id = ?", Integer.class,
                    inserted.getValue().eventId));
            var retry = f.service.create(f.passenger, request);
            assertNotNull(f.event(f.eventId(retry.id())));
        }
    }

    @Test void failureRemainsNewThenRetryCommitsSentAndDoesNotSendSentAgain() throws Exception {
        try (var f = new OutboxTestFixture()) {
            var order = f.create();
            long id = f.eventId(order.id());
            var sender = mock(OrderEventSender.class);
            doThrow(new TimeoutException("test confirm missing")).doNothing().when(sender).send(any());
            var publisher = new OutboxPublisher(f.outbox, sender, f.manager, 50);
            publisher.publishOne(id);
            assertEquals("NEW", f.event(id).status);
            assertEquals(1, f.event(id).attempts);
            assertNull(f.jdbc.queryForObject("SELECT sent_at FROM outbox_event WHERE id = ?", java.sql.Timestamp.class, id));
            publisher.publishOne(id);
            assertEquals("SENT", f.jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
            assertEquals(2, f.jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, id));
            assertNotNull(f.jdbc.queryForObject("SELECT sent_at FROM outbox_event WHERE id = ?", java.sql.Timestamp.class, id));
            publisher.publishOne(id);
            verify(sender, times(2)).send(any());
        }
    }

    @Test void twoPublishersSkipLockedEventAndOnlyOneSends() throws Exception {
        try (var f = new OutboxTestFixture(); var pool = Executors.newFixedThreadPool(2)) {
            long id = f.eventId(f.create().id());
            var sender = mock(OrderEventSender.class);
            var sending = new CountDownLatch(1);
            var confirm = new CountDownLatch(1);
            doAnswer(call -> {
                sending.countDown();
                assertTrue(confirm.await(10, TimeUnit.SECONDS));
                return null;
            }).when(sender).send(any());
            var first = new OutboxPublisher(f.outbox, sender, f.manager, 50);
            var second = new OutboxPublisher(f.outbox, sender, f.manager, 50);
            var winner = pool.submit(() -> first.publishOne(id));
            try {
                assertTrue(sending.await(10, TimeUnit.SECONDS));
                // Must finish without waiting for the other transaction's row lock.
                pool.submit(() -> second.publishOne(id)).get(3, TimeUnit.SECONDS);
            } finally {
                confirm.countDown();
            }
            winner.get(10, TimeUnit.SECONDS);
            verify(sender, times(1)).send(any());
            assertEquals("SENT", f.jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, id));
        }
    }

    @Test void failedSentCommitRollsBackAndReplayKeepsSameEventIdentity() throws Exception {
        try (var f = new OutboxTestFixture()) {
            long id = f.eventId(f.create().id());
            String eventId = f.event(id).eventId;
            var sender = mock(OrderEventSender.class);
            var faulty = mock(OutboxMapper.class);
            when(faulty.lockPending(id)).thenAnswer(call -> f.outbox.lockPending(id));
            when(faulty.recordAttempt(id, true)).thenAnswer(call -> {
                f.outbox.recordAttempt(id, true);
                throw new IllegalStateException("Injected failure after SENT update, before commit");
            });
            assertThrows(IllegalStateException.class,
                    () -> new OutboxPublisher(faulty, sender, f.manager, 50).publishOne(id));
            assertEquals("NEW", f.event(id).status);
            assertEquals(0, f.event(id).attempts);
            new OutboxPublisher(f.outbox, sender, f.manager, 50).publishOne(id);
            var replay = org.mockito.ArgumentCaptor.forClass(OutboxEvent.class);
            verify(sender, times(2)).send(replay.capture());
            assertTrue(replay.getAllValues().stream().allMatch(e -> eventId.equals(e.eventId)));
        }
    }
}

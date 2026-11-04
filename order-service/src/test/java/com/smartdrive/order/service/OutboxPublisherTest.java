package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartdrive.order.mapper.OutboxMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxPublisherTest {
    final OutboxMapper outbox = mock(OutboxMapper.class);
    final OrderEventSender sender = mock(OrderEventSender.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final OutboxPublisher publisher = new OutboxPublisher(outbox, sender, manager, 50);

    @Test void sentOnlyAfterSuccessfulConfirmation() throws Exception {
        var event = OrderEventSenderTest.event();
        when(outbox.lockPending(12)).thenReturn(event);
        when(outbox.recordAttempt(12, true)).thenReturn(1);
        publisher.publishOne(12);
        var order = inOrder(sender, outbox, manager);
        order.verify(sender).send(event);
        order.verify(outbox).recordAttempt(12, true);
        order.verify(manager).commit(any());
    }

    @Test void failureCommitsAttemptWhileLeavingEventNew() throws Exception {
        var event = OrderEventSenderTest.event();
        when(outbox.lockPending(12)).thenReturn(event);
        doThrow(new java.util.concurrent.TimeoutException()).when(sender).send(event);
        when(outbox.recordAttempt(12, false)).thenReturn(1);
        publisher.publishOne(12);
        verify(outbox).recordAttempt(12, false);
        verify(outbox, never()).recordAttempt(anyLong(), eq(true));
        verify(manager).commit(any());
    }

    @Test void alreadySentOrLockedByAnotherPublisherIsSkipped() {
        publisher.publishOne(12);
        verifyNoInteractions(sender);
        verify(outbox, never()).recordAttempt(anyLong(), anyBoolean());
    }

    @Test void databaseFailureDoesNotPreventLaterEventsInBatch() {
        when(outbox.pendingIds(50)).thenReturn(List.of(12L, 13L));
        when(outbox.lockPending(12)).thenThrow(new IllegalStateException("test database failure"));
        publisher.publishPending();
        verify(manager).rollback(any());
        verify(outbox).lockPending(13);
    }

    @Test void interruptKeepsEventRetryableAndPreservesThreadFlag() throws Exception {
        var event = OrderEventSenderTest.event();
        when(outbox.lockPending(12)).thenReturn(event);
        doThrow(new InterruptedException()).when(sender).send(event);
        when(outbox.recordAttempt(12, false)).thenReturn(1);
        try {
            publisher.publishOne(12);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(outbox).recordAttempt(12, false);
        } finally {
            Thread.interrupted();
        }
    }
}

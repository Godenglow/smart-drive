package com.smartdrive.order.service;

import com.smartdrive.order.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name = "smartdrive.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxMapper outbox;
    private final OrderEventSender sender;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public OutboxPublisher(OutboxMapper outbox, OrderEventSender sender,
            PlatformTransactionManager transactionManager,
            @Value("${smartdrive.outbox.batch-size:50}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid outbox batch size");
        this.outbox = outbox;
        this.sender = sender;
        this.transactions = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${smartdrive.outbox.poll-interval-ms:1000}",
            initialDelayString = "${smartdrive.outbox.poll-interval-ms:1000}")
    public void publishPending() {
        for (long id : outbox.pendingIds(batchSize)) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                publishOne(id);
            } catch (RuntimeException failure) {
                // Includes a failed status commit: the row remains retryable, duplicates are possible.
                log.error("Outbox transaction failed id={} cause={}", id, failure.getClass().getSimpleName());
            }
        }
    }

    public void publishOne(long id) {
        transactions.executeWithoutResult(transaction -> {
            var event = outbox.lockPending(id);
            if (event == null) return;
            boolean sent = false;
            try {
                sender.send(event);
                sent = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("Outbox send interrupted eventId={} orderId={}", event.eventId, event.aggregateId);
            } catch (Exception failure) {
                log.warn("Outbox send pending eventId={} orderId={} attempt={} cause={}",
                        event.eventId, event.aggregateId, event.attempts + 1, failure.getClass().getSimpleName());
            }
            if (outbox.recordAttempt(id, sent) != 1) throw new IllegalStateException("Outbox row changed while locked");
            if (sent) log.info("Outbox confirmed eventId={} orderId={}", event.eventId, event.aggregateId);
        });
    }
}

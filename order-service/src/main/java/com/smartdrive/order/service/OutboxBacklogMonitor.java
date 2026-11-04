package com.smartdrive.order.service;

import com.smartdrive.order.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxBacklogMonitor {
    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogMonitor.class);
    private final OutboxMapper outbox;

    public OutboxBacklogMonitor(OutboxMapper outbox) { this.outbox = outbox; }

    @Scheduled(fixedDelayString = "${smartdrive.mq-monitor.interval-ms:30000}",
            initialDelayString = "${smartdrive.mq-monitor.interval-ms:30000}")
    public void report() {
        try {
            var backlog = outbox.backlog();
            log.info("Outbox backlog pending={} oldestAgeMs={}", backlog.pending, backlog.oldestAgeMs);
        } catch (RuntimeException failure) {
            log.warn("Outbox backlog unavailable cause={}", failure.getClass().getSimpleName());
        }
    }
}

package com.smartdrive.driver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MatchingBacklogMonitor {
    private static final Logger log = LoggerFactory.getLogger(MatchingBacklogMonitor.class);
    private final AmqpAdmin admin;

    public MatchingBacklogMonitor(AmqpAdmin admin) { this.admin = admin; }

    @Scheduled(fixedDelayString = "${smartdrive.mq-monitor.interval-ms:30000}",
            initialDelayString = "${smartdrive.mq-monitor.interval-ms:30000}")
    public void report() {
        try {
            for (String name : new String[]{"driver.matching.q", "driver.matching.dlq"}) {
                var queue = admin.getQueueInfo(name);
                if (queue == null) {
                    log.warn("Matching backlog queue={} unavailable", name);
                } else if (name.endsWith(".dlq") && queue.getMessageCount() > 0) {
                    log.warn("Matching backlog queue={} ready={} consumers={}", name,
                            queue.getMessageCount(), queue.getConsumerCount());
                } else {
                    log.info("Matching backlog queue={} ready={} consumers={}", name,
                            queue.getMessageCount(), queue.getConsumerCount());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Matching backlog unavailable cause={}", failure.getClass().getSimpleName());
        }
    }
}

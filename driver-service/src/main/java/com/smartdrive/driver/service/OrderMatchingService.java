package com.smartdrive.driver.service;

import com.smartdrive.common.api.event.OrderCreatedEvent;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.mapper.OrderNoticeMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderMatchingService {
    private final OrderNoticeMapper notices;
    private final DriverService drivers;
    private final TransactionTemplate transaction;

    public OrderMatchingService(OrderNoticeMapper notices, DriverService drivers,
                                PlatformTransactionManager manager) {
        this.notices = notices;
        this.drivers = drivers;
        this.transaction = new TransactionTemplate(manager);
    }

    public void match(OrderCreatedEvent event) {
        validate(event);
        try {
            transaction.executeWithoutResult(status -> {
                notices.claim(event.orderId(), event.eventId());
                for (long driverId : drivers.nearby(new LocationRequest(event.pickupLng(), event.pickupLat()))) {
                    notices.insertNotice(event.orderId(), driverId);
                }
            });
        } catch (DuplicateKeyException conflict) {
            // Read after the losing transaction has rolled back, outside its repeatable-read snapshot.
            if (!event.eventId().equals(notices.processedEvent(event.orderId()))) throw conflict;
        }
    }

    static void validate(OrderCreatedEvent event) {
        if (event == null || !OrderCreatedEvent.TYPE.equals(event.type()) || event.orderId() <= 0
                || event.eventId() == null || event.occurredAt() == null
                || !coordinate(event.pickupLng(), "180") || !coordinate(event.pickupLat(), "85.05112878")) {
            throw new IllegalArgumentException("Invalid order.created event");
        }
        if (!UUID.fromString(event.eventId()).toString().equals(event.eventId())) {
            throw new IllegalArgumentException("Invalid eventId");
        }
    }

    private static boolean coordinate(BigDecimal value, String bound) {
        return value != null && value.abs().compareTo(new BigDecimal(bound)) <= 0;
    }
}

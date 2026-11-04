package com.smartdrive.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.event.OrderCreatedEvent;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.entity.OutboxEvent;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import com.smartdrive.order.mapper.OutboxMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderCreationTransaction {
    private final OrderMapper orders;
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public OrderCreationTransaction(OrderMapper orders, OutboxMapper outbox, ObjectMapper json,
                                    PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.outbox = outbox;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public OrderView create(RideOrder order) {
        return transactions.execute(transaction -> {
            orders.insert(order);
            OutboxEvent event = new OutboxEvent();
            event.eventId = UUID.randomUUID().toString();
            event.aggregateId = order.id;
            event.eventType = OrderCreatedEvent.TYPE;
            try {
                event.payload = json.writeValueAsString(new OrderCreatedEvent(event.eventId,
                        event.eventType, order.id, order.pickupLng, order.pickupLat, Instant.now()));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Cannot serialize order.created", failure);
            }
            outbox.insert(event);
            return orders.findById(order.id).view();
        });
    }
}

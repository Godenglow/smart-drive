package com.smartdrive.common.api.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

/** Stable wire contract shared with the driver matching consumer. */
public record OrderCreatedEvent(String eventId, String type, long orderId,
                                BigDecimal pickupLng, BigDecimal pickupLat,
                                @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {
    public static final String TYPE = "order.created";
}

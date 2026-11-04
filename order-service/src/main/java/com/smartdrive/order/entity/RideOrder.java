package com.smartdrive.order.entity;

import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.dto.Place;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class RideOrder {
    public Long id;
    public String requestId;
    public Long passengerId;
    public Long driverId;
    public String pickupName;
    public BigDecimal pickupLng;
    public BigDecimal pickupLat;
    public String dropoffName;
    public BigDecimal dropoffLng;
    public BigDecimal dropoffLat;
    public BigDecimal amount;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public OrderView view() {
        return new OrderView(id, requestId, passengerId, driverId,
                new Place(pickupName, pickupLng, pickupLat),
                new Place(dropoffName, dropoffLng, dropoffLat),
                amount, status, createdAt, updatedAt);
    }
}

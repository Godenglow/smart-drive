package com.smartdrive.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderView(long id, String requestId, long passengerId, Long driverId,
                        Place pickup, Place dropoff, BigDecimal amount, String status,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
}

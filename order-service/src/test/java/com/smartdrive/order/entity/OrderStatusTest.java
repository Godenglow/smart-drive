package com.smartdrive.order.entity;

import static org.junit.jupiter.api.Assertions.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderStatusTest {
    @Test void onlySpecifiedTransitionsAreAllowed() {
        Set<String> allowed = Set.of("WAITING:ACCEPTED", "WAITING:CANCELLED",
                "ACCEPTED:IN_SERVICE", "IN_SERVICE:FINISHED", "FINISHED:PAID");
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                boolean expected = allowed.contains(from + ":" + to);
                assertEquals(expected, from.canTransitionTo(to), from + " -> " + to);
                if (expected) {
                    assertDoesNotThrow(() -> from.requireTransitionTo(to));
                } else {
                    assertEquals(ErrorCode.ORDER_STATUS_INVALID, assertThrows(ApiException.class,
                            () -> from.requireTransitionTo(to)).errorCode());
                }
            }
        }
    }
}

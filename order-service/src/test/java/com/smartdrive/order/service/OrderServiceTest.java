package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.Place;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    @Mock OrderMapper mapper;
    @Mock NearbyDriverClient nearby;
    OrderService service;
    Principal passenger = new Principal(17, "PASSENGER");
    CreateOrderRequest request = new CreateOrderRequest(UUID.randomUUID(),
            new Place("天津站", new BigDecimal("117.215"), new BigDecimal("39.136")),
            new Place("南开大学", new BigDecimal("117.174"), new BigDecimal("39.104")));

    @BeforeEach void setup() { service = new OrderService(mapper, nearby); }

    @Test void noNearbyDriverDoesNotInsert() {
        ApiException error = assertThrows(ApiException.class, () -> service.create(passenger, request));
        assertEquals(ErrorCode.NO_DRIVER_NEARBY, error.errorCode());
        verify(mapper, never()).insert(any());
    }

    @Test void createsWaitingOrderAtFixedAmount() {
        when(nearby.hasNearby(any(), any())).thenReturn(true);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            RideOrder order = invocation.getArgument(0);
            assertEquals(new BigDecimal("50.00"), order.amount);
            assertEquals("WAITING", order.status);
            assertEquals(17L, order.passengerId);
            order.id = 8L;
            return 1;
        });
        RideOrder stored = order(8, 17, null);
        when(mapper.findById(8)).thenReturn(stored);
        assertEquals(8, service.create(passenger, request).id());
    }

    @Test void anotherPassengerCannotReadOrder() {
        when(mapper.findById(8)).thenReturn(order(8, 17, null));
        ApiException error = assertThrows(ApiException.class,
                () -> service.detail(new Principal(18, "PASSENGER"), 8));
        assertEquals(ErrorCode.FORBIDDEN, error.errorCode());
    }

    @Test void assignedDriverCanReadOwnOrder() {
        when(mapper.findById(8)).thenReturn(order(8, 17, 21L));
        assertEquals(8, service.detail(new Principal(21, "DRIVER"), 8).id());
    }

    @Test void mineIsScopedAndPaged() {
        when(mapper.countMine(17, "PASSENGER")).thenReturn(1L);
        when(mapper.findMine(17, "PASSENGER", 10, 10L)).thenReturn(List.of(order(8, 17, null)));
        var page = service.mine(passenger, 2, 10);
        assertEquals(1, page.total());
        assertEquals(8, page.items().getFirst().id());
    }

    private RideOrder order(long id, long passengerId, Long driverId) {
        RideOrder order = new RideOrder();
        order.id = id;
        order.requestId = request.requestId().toString();
        order.passengerId = passengerId;
        order.driverId = driverId;
        order.pickupName = request.pickup().name();
        order.pickupLng = request.pickup().lng();
        order.pickupLat = request.pickup().lat();
        order.dropoffName = request.dropoff().name();
        order.dropoffLng = request.dropoff().lng();
        order.dropoffLat = request.dropoff().lat();
        order.amount = new BigDecimal("50.00");
        order.status = "WAITING";
        return order;
    }
}

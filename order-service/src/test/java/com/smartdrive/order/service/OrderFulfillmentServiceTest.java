package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.entity.OrderStatus;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import org.junit.jupiter.api.Test;

class OrderFulfillmentServiceTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderFulfillmentService service = new OrderFulfillmentService(orders);
    private final Principal driver = new Principal(42, "DRIVER");
    private final Principal passenger = new Principal(17, "PASSENGER");

    @Test void rolesAreCheckedBeforeReadingOrder() {
        error(ErrorCode.FORBIDDEN, () -> service.start(passenger, 8));
        error(ErrorCode.FORBIDDEN, () -> service.finish(passenger, 8));
        error(ErrorCode.FORBIDDEN, () -> service.payDemo(driver, 8));
        verifyNoInteractions(orders);
    }

    @Test void missingOrdersReturnNotFound() {
        error(ErrorCode.NOT_FOUND, () -> service.start(driver, 8));
        error(ErrorCode.NOT_FOUND, () -> service.finish(driver, 8));
        error(ErrorCode.NOT_FOUND, () -> service.payDemo(passenger, 8));
    }

    @Test void wrongOwnerAndUnassignedDriverCannotChangeAnyState() {
        RideOrder order = order("PAID");
        when(orders.findById(8)).thenReturn(order);
        Principal other = new Principal(43, "DRIVER");
        error(ErrorCode.FORBIDDEN, () -> service.start(other, 8));
        error(ErrorCode.FORBIDDEN, () -> service.finish(other, 8));
        error(ErrorCode.FORBIDDEN, () -> service.payDemo(new Principal(18, "PASSENGER"), 8));
        order.driverId = null;
        error(ErrorCode.FORBIDDEN, () -> service.start(driver, 8));
        error(ErrorCode.FORBIDDEN, () -> service.finish(driver, 8));
        verify(orders, never()).startAccepted(anyLong(), anyLong());
        verify(orders, never()).finishInService(anyLong(), anyLong());
        verify(orders, never()).payFinished(anyLong(), anyLong());
        verify(orders, never()).releaseDriver(anyLong(), anyLong());
    }

    @Test void everyIllegalSourceStateIncludingDuplicatesAndTerminalStatesIsRejected() {
        for (OrderStatus status : OrderStatus.values()) {
            when(orders.findById(8)).thenReturn(order(status.name()));
            if (status != OrderStatus.ACCEPTED)
                error(ErrorCode.ORDER_STATUS_INVALID, () -> service.start(driver, 8));
            if (status != OrderStatus.IN_SERVICE)
                error(ErrorCode.ORDER_STATUS_INVALID, () -> service.finish(driver, 8));
            if (status != OrderStatus.FINISHED)
                error(ErrorCode.ORDER_STATUS_INVALID, () -> service.payDemo(passenger, 8));
        }
        verify(orders, never()).startAccepted(anyLong(), anyLong());
        verify(orders, never()).finishInService(anyLong(), anyLong());
        verify(orders, never()).payFinished(anyLong(), anyLong());
        verify(orders, never()).releaseDriver(anyLong(), anyLong());
    }

    @Test void losingConditionalUpdatesReturnConflictWithoutReleasingOccupation() {
        when(orders.findById(8)).thenReturn(order("ACCEPTED"));
        error(ErrorCode.ORDER_STATUS_INVALID, () -> service.start(driver, 8));
        when(orders.findById(8)).thenReturn(order("IN_SERVICE"));
        error(ErrorCode.ORDER_STATUS_INVALID, () -> service.finish(driver, 8));
        when(orders.findById(8)).thenReturn(order("FINISHED"));
        error(ErrorCode.ORDER_STATUS_INVALID, () -> service.payDemo(passenger, 8));
        verify(orders, never()).releaseDriver(anyLong(), anyLong());
    }

    @Test void inconsistentOccupationFailsSoTransactionCanRollBack() {
        when(orders.findById(8)).thenReturn(order("IN_SERVICE"));
        when(orders.finishInService(8, 42)).thenReturn(1);
        error(ErrorCode.INTERNAL_ERROR, () -> service.finish(driver, 8));
        verify(orders).releaseDriver(42, 8);
    }

    private RideOrder order(String status) {
        RideOrder order = new RideOrder();
        order.id = 8L;
        order.passengerId = 17L;
        order.driverId = 42L;
        order.status = status;
        return order;
    }

    private void error(ErrorCode expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiException.class, action::run).errorCode());
    }
}

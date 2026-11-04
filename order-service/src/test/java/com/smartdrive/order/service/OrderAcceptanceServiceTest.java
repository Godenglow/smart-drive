package com.smartdrive.order.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import org.junit.jupiter.api.Test;

class OrderAcceptanceServiceTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final NearbyDriverClient drivers = mock(NearbyDriverClient.class);
    private final OrderAcceptanceTransaction transaction = mock(OrderAcceptanceTransaction.class);
    private final OrderAcceptanceService service = new OrderAcceptanceService(orders, drivers, transaction);
    private final Principal driver = new Principal(42, "DRIVER");

    @Test void unauthorizedMissingAndInvalidStateNeverOpenTransaction() {
        rejects(ErrorCode.FORBIDDEN, new Principal(42, "PASSENGER"));
        rejects(ErrorCode.NOT_FOUND, driver);
        RideOrder cancelled = new RideOrder();
        cancelled.status = "CANCELLED";
        when(orders.findById(8)).thenReturn(cancelled);
        rejects(ErrorCode.ORDER_NOT_WAITING, driver);
        cancelled.driverId = 43L;
        cancelled.status = "ACCEPTED";
        rejects(ErrorCode.ORDER_ALREADY_TAKEN, driver);
        verifyNoInteractions(drivers, transaction);
    }

    @Test void ineligibleOrUnavailableDriversNeverOpenTransaction() {
        RideOrder waiting = new RideOrder();
        waiting.status = "WAITING";
        when(orders.findById(8)).thenReturn(waiting);
        for (ErrorCode code : new ErrorCode[]{ErrorCode.FORBIDDEN, ErrorCode.DRIVER_OFFLINE, ErrorCode.SERVICE_UNAVAILABLE}) {
            doThrow(new ApiException(code)).when(drivers).requireEligible(42, 8);
            rejects(code, driver);
        }
        verifyNoInteractions(transaction);
    }

    private void rejects(ErrorCode code, Principal principal) {
        assertEquals(code, assertThrows(ApiException.class, () -> service.accept(principal, 8)).errorCode());
    }
}

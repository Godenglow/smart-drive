package com.smartdrive.driver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.service.DriverService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InternalDriverControllerTest {
    private final DriverService drivers = Mockito.mock(DriverService.class);
    private final InternalDriverController controller = new InternalDriverController(drivers, "shared-secret");
    private final BigDecimal lng = new BigDecimal("121.4737");
    private final BigDecimal lat = new BigDecimal("31.2304");

    @Test
    void acceptsAuthorizedNearbyQuery() {
        when(drivers.nearby(new LocationRequest(lng, lat))).thenReturn(List.of(42L));
        controller.nearby("shared-secret", lng, lat);
        verify(drivers).nearby(new LocationRequest(lng, lat));
    }

    @Test
    void rejectsMissingOrWrongInternalToken() {
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(ApiException.class, () -> controller.nearby(null, lng, lat)).errorCode());
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(ApiException.class, () -> controller.nearby("wrong", lng, lat)).errorCode());
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        assertEquals(ErrorCode.INVALID_PARAM,
                assertThrows(ApiException.class, () -> controller.nearby("shared-secret", lng, new BigDecimal("86"))).errorCode());
    }
}

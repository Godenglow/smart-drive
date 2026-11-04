package com.smartdrive.driver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.service.DriverService;
import com.smartdrive.driver.service.DriverEligibilityService;
import com.smartdrive.driver.dto.EligibilityView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InternalDriverControllerTest {
    private final DriverService drivers = Mockito.mock(DriverService.class);
    private final DriverEligibilityService eligibility = Mockito.mock(DriverEligibilityService.class);
    private final InternalDriverController controller = new InternalDriverController(drivers, eligibility, "shared-secret");
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

    @Test void eligibilityRequiresInternalTokenAndPositiveIds() {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ApiException.class,
                () -> controller.eligibility(null, 42, 8)).errorCode());
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(ApiException.class,
                () -> controller.eligibility("wrong", 42, 8)).errorCode());
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(ApiException.class,
                () -> controller.eligibility("shared-secret", 0, 8)).errorCode());
        assertEquals(ErrorCode.INVALID_PARAM, assertThrows(ApiException.class,
                () -> controller.eligibility("shared-secret", 42, -1)).errorCode());
        when(eligibility.check(42, 8)).thenReturn(new EligibilityView(true, false));
        assertEquals(new EligibilityView(true, false), controller.eligibility("shared-secret", 42, 8).data());
    }
}

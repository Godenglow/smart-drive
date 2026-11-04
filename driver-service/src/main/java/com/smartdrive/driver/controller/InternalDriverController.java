package com.smartdrive.driver.controller;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.Result;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.dto.EligibilityView;
import com.smartdrive.driver.service.DriverService;
import com.smartdrive.driver.service.DriverEligibilityService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/drivers")
@Hidden
public class InternalDriverController {
    private final DriverService drivers;
    private final DriverEligibilityService eligibility;
    private final String internalToken;

    public InternalDriverController(DriverService drivers, DriverEligibilityService eligibility,
            @Value("${smartdrive.internal-token:}") String internalToken) {
        this.drivers = drivers;
        this.eligibility = eligibility;
        this.internalToken = internalToken;
    }

    @GetMapping("/nearby")
    public Result<List<Long>> nearby(@RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam BigDecimal lng, @RequestParam BigDecimal lat) {
        authenticate(token);
        if (lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0
                || lat.compareTo(new BigDecimal("-85.05112878")) < 0
                || lat.compareTo(new BigDecimal("85.05112878")) > 0) {
            throw new ApiException(ErrorCode.INVALID_PARAM);
        }
        return Result.ok(drivers.nearby(new LocationRequest(lng, lat)));
    }

    @GetMapping("/{driverId}/eligibility")
    public Result<EligibilityView> eligibility(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable long driverId, @RequestParam long orderId) {
        authenticate(token);
        if (driverId <= 0 || orderId <= 0) throw new ApiException(ErrorCode.INVALID_PARAM);
        return Result.ok(eligibility.check(driverId, orderId));
    }

    private void authenticate(String token) {
        if (internalToken.isBlank() || token == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
}

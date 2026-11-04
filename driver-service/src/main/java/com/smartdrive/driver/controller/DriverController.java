package com.smartdrive.driver.controller;

import com.smartdrive.common.api.Result;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.dto.OnlineView;
import com.smartdrive.driver.dto.ProfileRequest;
import com.smartdrive.driver.dto.ProfileView;
import com.smartdrive.driver.service.DriverService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers/me")
public class DriverController {
    private final DriverService drivers;

    public DriverController(DriverService drivers) {
        this.drivers = drivers;
    }

    @PutMapping("/profile")
    public Result<ProfileView> profile(HttpServletRequest request, @Valid @RequestBody ProfileRequest body) {
        return Result.ok(drivers.saveProfile(userId(request), body.displayName()));
    }

    @PutMapping("/online")
    public Result<OnlineView> online(HttpServletRequest request, @Valid @RequestBody LocationRequest body) {
        return Result.ok(drivers.online(userId(request), body));
    }

    @PutMapping("/location")
    public Result<OnlineView> location(HttpServletRequest request, @Valid @RequestBody LocationRequest body) {
        return Result.ok(drivers.updateLocation(userId(request), body));
    }

    @PutMapping("/offline")
    public Result<OnlineView> offline(HttpServletRequest request) {
        return Result.ok(drivers.offline(userId(request)));
    }

    private long userId(HttpServletRequest request) {
        return ((Principal) request.getAttribute(ServletAuthFilter.PRINCIPAL_ATTRIBUTE)).userId();
    }
}

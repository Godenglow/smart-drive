package com.smartdrive.driver.controller;

import com.smartdrive.common.api.Result;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.dto.DriverStateView;
import com.smartdrive.driver.dto.OnlineView;
import com.smartdrive.driver.dto.ProfileRequest;
import com.smartdrive.driver.dto.ProfileView;
import com.smartdrive.driver.service.DriverService;
import com.smartdrive.driver.service.OrderNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drivers/me")
@Tag(name = "司机工作台")
public class DriverController {
    private final DriverService drivers;
    private final OrderNoticeService notices;

    public DriverController(DriverService drivers, OrderNoticeService notices) {
        this.drivers = drivers;
        this.notices = notices;
    }

    @GetMapping("/notices")
    @Operation(summary = "查询本人待抢提示", description = "返回最近通知的订单 ID，默认 NEW、最多 100 条。通知代表曾获候选资格，当前状态须查询订单详情，抢单以订单事务为准。")
    public Result<List<Long>> notices(HttpServletRequest request,
            @RequestParam(defaultValue = "NEW") String status,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(notices.list(userId(request), status, limit));
    }

    @GetMapping("/status")
    @Operation(summary = "读取本人资料及在线状态", description = "无资料时 profile 为 null；在线状态以 Redis 有效标记为准，读取不会续期。")
    public Result<DriverStateView> status(HttpServletRequest request) {
        return Result.ok(drivers.state(userId(request)));
    }

    @PutMapping("/profile")
    @Operation(summary = "创建或更新本人司机资料")
    public Result<ProfileView> profile(HttpServletRequest request, @Valid @RequestBody ProfileRequest body) {
        return Result.ok(drivers.saveProfile(userId(request), body.displayName()));
    }

    @PutMapping("/online")
    @Operation(summary = "上线并写入位置", description = "在线标记有效期 120 秒，坐标使用经度 lng、纬度 lat。")
    public Result<OnlineView> online(HttpServletRequest request, @Valid @RequestBody LocationRequest body) {
        return Result.ok(drivers.online(userId(request), body));
    }

    @PutMapping("/location")
    @Operation(summary = "位置心跳", description = "在线时更新位置并续期 120 秒；建议每 30 秒一次。")
    public Result<OnlineView> location(HttpServletRequest request, @Valid @RequestBody LocationRequest body) {
        return Result.ok(drivers.updateLocation(userId(request), body));
    }

    @PutMapping("/offline")
    @Operation(summary = "下线", description = "移除在线标记及 GEO 成员，可重复调用。")
    public Result<OnlineView> offline(HttpServletRequest request) {
        return Result.ok(drivers.offline(userId(request)));
    }

    private long userId(HttpServletRequest request) {
        return ((Principal) request.getAttribute(ServletAuthFilter.PRINCIPAL_ATTRIBUTE)).userId();
    }
}

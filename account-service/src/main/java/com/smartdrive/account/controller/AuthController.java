package com.smartdrive.account.controller;

import com.smartdrive.account.dto.RegisterRequest;
import com.smartdrive.account.dto.RegisterResponse;
import com.smartdrive.account.service.AccountService;
import com.smartdrive.common.api.ApiError;
import com.smartdrive.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 注册（公开）；登录在 Day 5 加入同一前缀 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "注册（Day 4）、登录（Day 5）")
public class AuthController {

    private final AccountService accountService;

    public AuthController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/register")
    @Operation(summary = "注册账户",
            description = "role 只允许 PASSENGER 或 DRIVER；手机号唯一，重复注册返回 409；密码以 BCrypt 哈希存储。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "注册成功，返回 userId"),
            @ApiResponse(responseCode = "400", description = "参数不合法：手机号格式、密码长度、非法角色、请求体缺失",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "手机号已注册",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(new RegisterResponse(accountService.register(request)));
    }
}

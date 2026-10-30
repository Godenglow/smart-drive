package com.smartdrive.account.controller;

import com.smartdrive.account.dto.RegisterRequest;
import com.smartdrive.account.dto.RegisterResponse;
import com.smartdrive.account.dto.LoginRequest;
import com.smartdrive.account.dto.LoginResponse;
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

/** 注册与登录公开接口 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "注册与登录")
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

    @PostMapping("/login")
    @Operation(summary = "登录", description = "使用手机号和密码登录，返回有效期两小时的 JWT Bearer 令牌。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功"),
            @ApiResponse(responseCode = "400", description = "缺少手机号或密码", content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "手机号或密码错误", content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(accountService.login(request.phone(), request.password()));
    }
}

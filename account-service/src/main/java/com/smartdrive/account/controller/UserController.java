package com.smartdrive.account.controller;

import com.smartdrive.account.dto.AccountView;
import com.smartdrive.common.api.ApiError;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.Result;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.account.entity.Account;
import com.smartdrive.account.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/** 当前登录账户信息 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "账户", description = "当前登录账户信息（需 JWT）")
public class UserController {
    private final AccountService accountService;

    public UserController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/me")
    @Operation(summary = "查询当前账户", description = "需要 Bearer JWT；返回 userId、phone、role。")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前账户信息"),
            @ApiResponse(responseCode = "401", description = "未登录或令牌无效",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Result<AccountView> me(HttpServletRequest request) {
        Principal principal = (Principal) request.getAttribute(ServletAuthFilter.PRINCIPAL_ATTRIBUTE);
        Account account = accountService.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!account.getRole().equals(principal.role())) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return Result.ok(new AccountView(account.getId(), account.getPhone(), account.getRole()));
    }
}

package com.smartdrive.account.controller;

import com.smartdrive.account.dto.AccountView;
import com.smartdrive.common.api.ApiError;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前登录账户信息；接口形态按规格文档 §7.2 固定，鉴权（JWT）Day 5 接入后才会返回数据 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "账户", description = "当前登录账户信息（需 JWT，Day 5 开放）")
public class UserController {

    @GetMapping("/me")
    @Operation(summary = "查询当前账户",
            description = "需要登录：返回 userId、phone、role。JWT 鉴权在 Day 5 接入，在此之前本接口一律返回 401。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前账户信息（Day 5 起可用）"),
            @ApiResponse(responseCode = "401", description = "未登录或令牌无效（当前阶段固定返回）",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public Result<AccountView> me() {
        // 未接入 JWT 前不返回任何账户数据；Day 5 在此解析当前用户并调用 AccountService.findById
        throw new ApiException(ErrorCode.UNAUTHORIZED);
    }
}

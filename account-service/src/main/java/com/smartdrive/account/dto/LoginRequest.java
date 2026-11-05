package com.smartdrive.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "1[3-9]\\d{9}", message = "手机号需为 1[3-9] 开头的 11 位数字") String phone,
        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码最多 64 位") String password) {}

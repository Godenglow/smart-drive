package com.smartdrive.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 注册请求（规格文档 §7.2）；role 的合法值在 AccountService 中校验，以给出更明确的错误文案 */
public record RegisterRequest(

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "1[3-9]\\d{9}", message = "手机号需为 1[3-9] 开头的 11 位数字")
        String phone,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8~64 位")
        String password,

        @NotBlank(message = "角色不能为空")
        String role) {
}

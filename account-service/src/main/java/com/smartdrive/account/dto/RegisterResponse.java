package com.smartdrive.account.dto;

/** 注册成功响应：只回 userId，不暴露账户其他字段 */
public record RegisterResponse(long userId) {
}

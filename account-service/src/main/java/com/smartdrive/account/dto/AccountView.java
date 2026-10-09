package com.smartdrive.account.dto;

/** GET /api/users/me 的响应（规格文档 §7.2）：不含密码等敏感字段 */
public record AccountView(long userId, String phone, String role) {
}

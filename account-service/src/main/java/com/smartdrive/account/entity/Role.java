package com.smartdrive.account.entity;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;

/** 注册角色（规格文档 §4.1）：V1 只允许乘客与司机 */
public enum Role {

    PASSENGER,
    DRIVER;

    /** 解析客户端传入的 role，非法值按参数错误处理，给出明确文案 */
    public static Role parse(String raw) {
        for (Role role : values()) {
            if (role.name().equals(raw)) {
                return role;
            }
        }
        throw new ApiException(ErrorCode.INVALID_PARAM, "role 只能是 PASSENGER 或 DRIVER");
    }
}

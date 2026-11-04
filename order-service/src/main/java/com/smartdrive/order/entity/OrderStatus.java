package com.smartdrive.order.entity;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;

public enum OrderStatus {
    WAITING, ACCEPTED, IN_SERVICE, FINISHED, PAID, CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case WAITING -> target == ACCEPTED || target == CANCELLED;
            case ACCEPTED -> target == IN_SERVICE;
            case IN_SERVICE -> target == FINISHED;
            case FINISHED -> target == PAID;
            case PAID, CANCELLED -> false;
        };
    }

    public void requireTransitionTo(OrderStatus target) {
        if (!canTransitionTo(target)) throw new ApiException(ErrorCode.ORDER_STATUS_INVALID);
    }
}

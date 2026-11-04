package com.smartdrive.order.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderAcceptanceTransaction {
    private final OrderMapper orders;

    public OrderAcceptanceTransaction(OrderMapper orders) { this.orders = orders; }

    @Transactional
    public OrderView accept(long driverId, long orderId) {
        orders.occupyDriver(driverId, orderId);
        if (orders.acceptWaiting(orderId, driverId) != 1) {
            // Throw out of the proxy: the just-inserted occupation must roll back.
            throw new ApiException(ErrorCode.ORDER_NOT_WAITING);
        }
        return orders.findById(orderId).view();
    }
}

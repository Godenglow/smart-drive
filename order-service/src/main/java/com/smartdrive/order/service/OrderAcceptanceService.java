package com.smartdrive.order.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.entity.OrderStatus;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderAcceptanceService {
    private static final Logger log = LoggerFactory.getLogger(OrderAcceptanceService.class);
    private final OrderMapper orders;
    private final NearbyDriverClient drivers;
    private final OrderAcceptanceTransaction transaction;

    public OrderAcceptanceService(OrderMapper orders, NearbyDriverClient drivers,
            OrderAcceptanceTransaction transaction) {
        this.orders = orders;
        this.drivers = drivers;
        this.transaction = transaction;
    }

    public OrderView accept(Principal principal, long orderId) {
        if (!"DRIVER".equals(principal.role())) throw new ApiException(ErrorCode.FORBIDDEN);
        RideOrder order = orders.findById(orderId);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND);
        if (order.driverId != null) throw new ApiException(ErrorCode.ORDER_ALREADY_TAKEN);
        if (!OrderStatus.valueOf(order.status).canTransitionTo(OrderStatus.ACCEPTED)) {
            throw new ApiException(ErrorCode.ORDER_NOT_WAITING);
        }
        // Remote checks run before opening a database transaction or holding any row locks.
        drivers.requireEligible(principal.userId(), orderId);
        OrderView accepted;
        try {
            accepted = transaction.accept(principal.userId(), orderId);
        } catch (DuplicateKeyException conflict) {
            // The transaction has already rolled back; this read sees committed winners.
            throw new ApiException(orders.findActiveOrder(principal.userId()) != null
                    ? ErrorCode.DRIVER_BUSY : ErrorCode.ORDER_ALREADY_TAKEN);
        }
        log.info("orderId={} driverId={} status=ACCEPTED", orderId, principal.userId());
        return accepted;
    }
}

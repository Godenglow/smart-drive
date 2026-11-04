package com.smartdrive.order.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.entity.OrderStatus;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderFulfillmentService {
    private final OrderMapper orders;

    public OrderFulfillmentService(OrderMapper orders) { this.orders = orders; }

    @Transactional
    public OrderView start(Principal principal, long id) {
        requireDriverOrder(principal, id, OrderStatus.IN_SERVICE);
        requireChanged(orders.startAccepted(id, principal.userId()));
        return orders.findById(id).view();
    }

    @Transactional
    public OrderView finish(Principal principal, long id) {
        requireDriverOrder(principal, id, OrderStatus.FINISHED);
        requireChanged(orders.finishInService(id, principal.userId()));
        // Delete only this order's occupation. Any inconsistency rolls the status back too.
        if (orders.releaseDriver(principal.userId(), id) != 1) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        return orders.findById(id).view();
    }

    /** Demo state change only: the amount is never charged or supplied by the caller. */
    @Transactional
    public OrderView payDemo(Principal principal, long id) {
        requireRole(principal, "PASSENGER");
        RideOrder order = requireOrder(id);
        if (order.passengerId != principal.userId()) throw new ApiException(ErrorCode.FORBIDDEN);
        OrderStatus.valueOf(order.status).requireTransitionTo(OrderStatus.PAID);
        requireChanged(orders.payFinished(id, principal.userId()));
        return orders.findById(id).view();
    }

    private void requireDriverOrder(Principal principal, long id, OrderStatus target) {
        requireRole(principal, "DRIVER");
        RideOrder order = requireOrder(id);
        if (order.driverId == null || order.driverId != principal.userId()) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        OrderStatus.valueOf(order.status).requireTransitionTo(target);
    }

    private RideOrder requireOrder(long id) {
        RideOrder order = orders.findById(id);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND);
        return order;
    }

    private void requireRole(Principal principal, String role) {
        if (!role.equals(principal.role())) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    private void requireChanged(int rows) {
        if (rows != 1) throw new ApiException(ErrorCode.ORDER_STATUS_INVALID);
    }
}

package com.smartdrive.order.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.OrderPage;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.entity.OrderStatus;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import java.math.BigDecimal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private static final BigDecimal DEMO_AMOUNT = new BigDecimal("50.00");
    private final OrderMapper orders;
    private final NearbyDriverClient nearby;
    private final OrderCreationTransaction creation;

    public OrderService(OrderMapper orders, NearbyDriverClient nearby, OrderCreationTransaction creation) {
        this.orders = orders;
        this.nearby = nearby;
        this.creation = creation;
    }

    public OrderView create(Principal principal, CreateOrderRequest request) {
        if (!"PASSENGER".equals(principal.role())) throw new ApiException(ErrorCode.FORBIDDEN);
        String requestId = request.requestId().toString();
        RideOrder existing = orders.findByRequest(principal.userId(), requestId);
        if (existing != null) return existing.view();
        if (!nearby.hasNearby(request.pickup().lng(), request.pickup().lat())) {
            throw new ApiException(ErrorCode.NO_DRIVER_NEARBY);
        }
        RideOrder order = new RideOrder();
        order.requestId = requestId;
        order.passengerId = principal.userId();
        order.pickupName = request.pickup().name().trim();
        order.pickupLng = request.pickup().lng();
        order.pickupLat = request.pickup().lat();
        order.dropoffName = request.dropoff().name().trim();
        order.dropoffLng = request.dropoff().lng();
        order.dropoffLat = request.dropoff().lat();
        order.amount = DEMO_AMOUNT;
        order.status = OrderStatus.WAITING.name();
        // Catch outside the order+outbox transaction. Its rollback completes before
        // the winner is read, so no repeatable-read snapshot hides the committed row.
        try {
            return creation.create(order);
        } catch (DuplicateKeyException conflict) {
            RideOrder winner = orders.findByRequest(principal.userId(), requestId);
            if (winner == null) throw conflict;
            return winner.view();
        }
    }

    public OrderView cancel(Principal principal, long id) {
        if (!"PASSENGER".equals(principal.role())) throw new ApiException(ErrorCode.FORBIDDEN);
        RideOrder order = orders.findById(id);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND);
        if (order.passengerId != principal.userId()) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!OrderStatus.valueOf(order.status).canTransitionTo(OrderStatus.CANCELLED)) {
            throw new ApiException(ErrorCode.ORDER_NOT_WAITING);
        }
        // The condition is checked again by MySQL, so a concurrent accept/cancel wins once.
        if (orders.cancelWaiting(id, principal.userId()) != 1) {
            throw new ApiException(ErrorCode.ORDER_NOT_WAITING);
        }
        return orders.findById(id).view();
    }

    public OrderView detail(Principal principal, long id) {
        RideOrder order = orders.findById(id);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND);
        if (!("PASSENGER".equals(principal.role()) && order.passengerId == principal.userId())
                && !("DRIVER".equals(principal.role()) && order.driverId != null
                    && order.driverId == principal.userId())) {
            if ("DRIVER".equals(principal.role()) && nearby.wasNotified(principal.userId(), id)) {
                // Candidate drivers need places, amount and live status; account/request/assignment IDs stay private.
                OrderView view = order.view();
                return new OrderView(view.id(), null, null, null, view.pickup(), view.dropoff(),
                        view.amount(), view.status(), view.createdAt(), view.updatedAt());
            }
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return order.view();
    }

    public OrderPage mine(Principal principal, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new ApiException(ErrorCode.INVALID_PARAM);
        long offset = ((long) page - 1) * size;
        return new OrderPage(page, size, orders.countMine(principal.userId(), principal.role()),
                orders.findMine(principal.userId(), principal.role(), size, offset)
                        .stream().map(RideOrder::view).toList());
    }
}

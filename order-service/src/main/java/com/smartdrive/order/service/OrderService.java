package com.smartdrive.order.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.OrderPage;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private static final BigDecimal DEMO_AMOUNT = new BigDecimal("50.00");
    private final OrderMapper orders;
    private final NearbyDriverClient nearby;

    public OrderService(OrderMapper orders, NearbyDriverClient nearby) {
        this.orders = orders;
        this.nearby = nearby;
    }

    public OrderView create(Principal principal, CreateOrderRequest request) {
        if (!"PASSENGER".equals(principal.role())) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!nearby.hasNearby(request.pickup().lng(), request.pickup().lat())) {
            throw new ApiException(ErrorCode.NO_DRIVER_NEARBY);
        }
        RideOrder order = new RideOrder();
        order.requestId = request.requestId().toString();
        order.passengerId = principal.userId();
        order.pickupName = request.pickup().name().trim();
        order.pickupLng = request.pickup().lng();
        order.pickupLat = request.pickup().lat();
        order.dropoffName = request.dropoff().name().trim();
        order.dropoffLng = request.dropoff().lng();
        order.dropoffLat = request.dropoff().lat();
        order.amount = DEMO_AMOUNT;
        order.status = "WAITING";
        orders.insert(order);
        return orders.findById(order.id).view();
    }

    public OrderView detail(Principal principal, long id) {
        RideOrder order = orders.findById(id);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND);
        if (!("PASSENGER".equals(principal.role()) && order.passengerId == principal.userId())
                && !("DRIVER".equals(principal.role()) && order.driverId != null
                    && order.driverId == principal.userId())) {
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

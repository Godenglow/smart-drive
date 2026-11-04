package com.smartdrive.order.controller;

import com.smartdrive.common.api.Result;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.OrderPage;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.service.OrderService;
import com.smartdrive.order.service.OrderAcceptanceService;
import com.smartdrive.order.service.OrderFulfillmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    private final OrderAcceptanceService acceptance;
    private final OrderFulfillmentService fulfillment;

    public OrderController(OrderService orders, OrderAcceptanceService acceptance, OrderFulfillmentService fulfillment) {
        this.orders = orders;
        this.acceptance = acceptance;
        this.fulfillment = fulfillment;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<OrderView> create(HttpServletRequest request, @Valid @RequestBody CreateOrderRequest body) {
        return Result.ok(orders.create(principal(request), body));
    }

    @GetMapping("/mine")
    public Result<OrderPage> mine(HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return Result.ok(orders.mine(principal(request), page, size));
    }

    @GetMapping("/{id}")
    public Result<OrderView> detail(HttpServletRequest request, @PathVariable long id) {
        return Result.ok(orders.detail(principal(request), id));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderView> cancel(HttpServletRequest request, @PathVariable long id) {
        return Result.ok(orders.cancel(principal(request), id));
    }

    @PostMapping("/{id}/accept")
    public Result<OrderView> accept(HttpServletRequest request, @PathVariable long id) {
        return Result.ok(acceptance.accept(principal(request), id));
    }

    @PostMapping("/{id}/start")
    public Result<OrderView> start(HttpServletRequest request, @PathVariable long id) {
        return Result.ok(fulfillment.start(principal(request), id));
    }

    @PostMapping("/{id}/finish")
    public Result<OrderView> finish(HttpServletRequest request, @PathVariable long id) {
        return Result.ok(fulfillment.finish(principal(request), id));
    }

    @PostMapping("/{id}/pay-demo")
    public Result<OrderView> payDemo(HttpServletRequest request, @PathVariable long id) {
        return Result.ok(fulfillment.payDemo(principal(request), id));
    }

    private Principal principal(HttpServletRequest request) {
        return (Principal) request.getAttribute(ServletAuthFilter.PRINCIPAL_ATTRIBUTE);
    }
}

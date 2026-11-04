package com.smartdrive.order.controller;

import com.smartdrive.common.api.Result;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.order.dto.CreateOrderRequest;
import com.smartdrive.order.dto.OrderPage;
import com.smartdrive.order.dto.OrderView;
import com.smartdrive.order.service.OrderService;
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

    public OrderController(OrderService orders) { this.orders = orders; }

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

    private Principal principal(HttpServletRequest request) {
        return (Principal) request.getAttribute(ServletAuthFilter.PRINCIPAL_ATTRIBUTE);
    }
}

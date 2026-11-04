package com.smartdrive.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.common.api.auth.Principal;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.common.api.web.GlobalExceptionHandler;
import com.smartdrive.order.entity.RideOrder;
import com.smartdrive.order.mapper.OrderMapper;
import com.smartdrive.order.service.NearbyDriverClient;
import com.smartdrive.order.service.OrderService;
import com.smartdrive.order.service.OrderCreationTransaction;
import com.smartdrive.order.mapper.OutboxMapper;
import org.springframework.transaction.PlatformTransactionManager;
import com.smartdrive.order.service.OrderAcceptanceService;
import com.smartdrive.order.service.OrderAcceptanceTransaction;
import com.smartdrive.order.service.OrderFulfillmentService;
import org.springframework.dao.DuplicateKeyException;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderControllerTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final NearbyDriverClient nearby = mock(NearbyDriverClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final JwtCodec jwt = new JwtCodec("day9-test-secret-longer-than-thirty-two-bytes", json, Clock.systemUTC());
    private MockMvc mvc;
    private String passenger;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(new OrderService(orders, nearby,
                        new OrderCreationTransaction(orders, mock(OutboxMapper.class),
                                new ObjectMapper().findAndRegisterModules(), mock(PlatformTransactionManager.class))),
                        new OrderAcceptanceService(orders, nearby, new OrderAcceptanceTransaction(orders)),
                        new OrderFulfillmentService(orders)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ServletAuthFilter(jwt, json)).build();
        passenger = token(17, "PASSENGER");
    }

    @Test void createAndRetryReturnSameOrderThenCancelReturnsConflictOnRepeat() throws Exception {
        String requestId = UUID.randomUUID().toString();
        RideOrder stored = new RideOrder();
        stored.id = 8L;
        stored.passengerId = 17L;
        stored.requestId = requestId;
        stored.status = "WAITING";
        when(nearby.hasNearby(any(), any())).thenReturn(true);
        when(orders.insert(any())).thenAnswer(call -> {
            RideOrder inserted = call.getArgument(0);
            inserted.id = 8L;
            return 1;
        });
        when(orders.findById(8)).thenReturn(stored);
        String body = """
                {"requestId":"%s","pickup":{"name":"天津站","lng":117.215,"lat":39.136},
                "dropoff":{"name":"南开大学","lng":117.174,"lat":39.104}}
                """.formatted(requestId);
        mvc.perform(post("/api/orders").header("Authorization", passenger)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(8));
        when(orders.findByRequest(17, requestId)).thenReturn(stored);
        mvc.perform(post("/api/orders").header("Authorization", passenger)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(8));
        verify(orders, times(1)).insert(any());
        when(orders.cancelWaiting(8, 17)).thenAnswer(call -> {
            stored.status = "CANCELLED";
            return 1;
        });
        mvc.perform(post("/api/orders/8/cancel").header("Authorization", passenger))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/8/cancel").header("Authorization", passenger))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_NOT_WAITING"));
    }

    @Test void cancelRequiresAuthenticationRoleAndOwnership() throws Exception {
        mvc.perform(post("/api/orders/8/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/8/cancel").header("Authorization", token(17, "DRIVER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orders);
        RideOrder other = new RideOrder();
        other.passengerId = 18L;
        when(orders.findById(8)).thenReturn(other);
        mvc.perform(post("/api/orders/8/cancel").header("Authorization", passenger))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(orders, never()).cancelWaiting(8, 17);
        mvc.perform(post("/api/orders/999/cancel").header("Authorization", passenger))
                .andExpect(status().isNotFound());
    }

    @Test void malformedCreateAndIdHaveClearErrors() throws Exception {
        mvc.perform(post("/api/orders").header("Authorization", passenger)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
        mvc.perform(post("/api/orders/invalid/cancel").header("Authorization", passenger))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orders, nearby);
    }

    private String token(long userId, String role) {
        return "Bearer " + jwt.issue(userId, role, Instant.now().plusSeconds(3600));
    }

    @Test void acceptAuthenticatesAndReturnsAcceptedOwnership() throws Exception {
        mvc.perform(post("/api/orders/8/accept")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/8/accept").header("Authorization", passenger))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orders, nearby);
        RideOrder stored = new RideOrder();
        stored.id = 8L;
        stored.passengerId = 17L;
        stored.status = "WAITING";
        when(orders.findById(8)).thenReturn(stored);
        when(orders.acceptWaiting(8, 42)).thenAnswer(call -> {
            stored.driverId = 42L;
            stored.status = "ACCEPTED";
            return 1;
        });
        mvc.perform(post("/api/orders/8/accept").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.driverId").value(42))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mvc.perform(get("/api/orders/8").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/8").header("Authorization", token(43, "DRIVER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/8/accept").header("Authorization", token(43, "DRIVER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_ALREADY_TAKEN"));
        mvc.perform(post("/api/orders/999/accept").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isNotFound());
    }

    @Test void busyDriverConflictHasPublicErrorCode() throws Exception {
        RideOrder waiting = new RideOrder();
        waiting.status = "WAITING";
        when(orders.findById(8)).thenReturn(waiting);
        when(orders.occupyDriver(42, 8)).thenThrow(new DuplicateKeyException("driver"));
        when(orders.findActiveOrder(42)).thenReturn(9L);
        mvc.perform(post("/api/orders/8/accept").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRIVER_BUSY"));
    }

    @Test void fulfillmentAndDemoPaymentReturnPersistedStatesAndBothHistories() throws Exception {
        RideOrder stored = new RideOrder();
        stored.id = 8L;
        stored.passengerId = 17L;
        stored.driverId = 42L;
        stored.amount = new BigDecimal("50.00");
        stored.status = "ACCEPTED";
        when(orders.findById(8)).thenReturn(stored);
        when(orders.startAccepted(8, 42)).thenAnswer(call -> { stored.status = "IN_SERVICE"; return 1; });
        when(orders.finishInService(8, 42)).thenAnswer(call -> { stored.status = "FINISHED"; return 1; });
        when(orders.releaseDriver(42, 8)).thenReturn(1);
        when(orders.payFinished(8, 17)).thenAnswer(call -> { stored.status = "PAID"; return 1; });
        mvc.perform(post("/api/orders/8/finish").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATUS_INVALID"));
        mvc.perform(post("/api/orders/8/start").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_SERVICE"));
        mvc.perform(post("/api/orders/8/finish").header("Authorization", token(42, "DRIVER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
        mvc.perform(post("/api/orders/8/pay-demo").header("Authorization", passenger))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.amount").value(50.00));
        mvc.perform(post("/api/orders/8/pay-demo").header("Authorization", passenger))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATUS_INVALID"));
        verify(orders, times(1)).releaseDriver(42, 8);
        verify(orders, times(1)).payFinished(8, 17);
        for (var actor : List.of(new Principal(17, "PASSENGER"), new Principal(42, "DRIVER"))) {
            when(orders.countMine(actor.userId(), actor.role())).thenReturn(1L);
            when(orders.findMine(actor.userId(), actor.role(), 10, 0)).thenReturn(List.of(stored));
            mvc.perform(get("/api/orders/mine").header("Authorization", token(actor.userId(), actor.role())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].status").value("PAID"));
        }
    }

    @Test void fulfillmentAuthenticatesRolesAndOrderOwners() throws Exception {
        for (String action : List.of("start", "finish", "pay-demo")) {
            mvc.perform(post("/api/orders/8/" + action)).andExpect(status().isUnauthorized());
            String wrongRole = action.equals("pay-demo") ? token(42, "DRIVER") : passenger;
            mvc.perform(post("/api/orders/8/" + action).header("Authorization", wrongRole))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(orders);
        RideOrder stored = new RideOrder();
        stored.passengerId = 17L;
        stored.driverId = 42L;
        when(orders.findById(8)).thenReturn(stored);
        for (String action : List.of("start", "finish", "pay-demo")) {
            String stranger = action.equals("pay-demo") ? token(18, "PASSENGER") : token(43, "DRIVER");
            mvc.perform(post("/api/orders/8/" + action).header("Authorization", stranger))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            String owner = action.equals("pay-demo") ? passenger : token(42, "DRIVER");
            mvc.perform(post("/api/orders/999/" + action).header("Authorization", owner))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/orders/invalid/" + action).header("Authorization", owner))
                    .andExpect(status().isBadRequest());
        }
    }
}

package com.smartdrive.driver.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.driver.dto.DriverStateView;
import com.smartdrive.driver.service.DriverService;
import com.smartdrive.driver.service.OrderNoticeService;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DriverStateControllerTest {
    @Test void alternateDriverPathsCannotReadOrMutateDriverState() throws Exception {
        var drivers = mock(DriverService.class);
        var notices = mock(OrderNoticeService.class);
        var jwt = new JwtCodec("step17-test-secret-at-least-thirty-two-bytes", new ObjectMapper(), java.time.Clock.systemUTC());
        var mvc = MockMvcBuilders.standaloneSetup(new DriverController(drivers, notices))
                .addFilters(new ServletAuthFilter(jwt, new ObjectMapper())).build();
        String passenger = jwt.issue(17, "PASSENGER", Instant.now().plusSeconds(3600));
        for (String prefix : new String[]{"/api/drivers;x=1", "/api/%64rivers", "/%61pi/drivers", "/api;x=1/drivers"}) {
            for (String endpoint : new String[]{"profile", "online", "location", "offline", "notices", "status"}) {
                HttpMethod method = endpoint.equals("notices") || endpoint.equals("status") ? HttpMethod.GET : HttpMethod.PUT;
                mvc.perform(request(method, URI.create(prefix + "/me/" + endpoint))
                                .header("Authorization", "Bearer " + passenger).contentType("application/json").content("{}"))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAM"));
            }
        }
        verifyNoInteractions(drivers, notices);
    }

    @Test void stateUsesJwtIdentityAndRejectsOtherRoles() throws Exception {
        var drivers = mock(DriverService.class);
        var jwt = new JwtCodec("step17-test-secret-at-least-thirty-two-bytes", new ObjectMapper(), java.time.Clock.systemUTC());
        var mvc = MockMvcBuilders.standaloneSetup(new DriverController(drivers, mock(OrderNoticeService.class)))
                .addFilters(new ServletAuthFilter(jwt, new ObjectMapper())).build();
        when(drivers.state(42)).thenReturn(new DriverStateView(null, false));
        mvc.perform(get("/api/drivers/me/status?userId=99").header("Authorization", "Bearer "
                + jwt.issue(42, "DRIVER", java.time.Instant.now().plusSeconds(3600))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.online").value(false))
                .andExpect(jsonPath("$.data.profile").doesNotExist());
        mvc.perform(get("/api/drivers/me/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/drivers/me/status").header("Authorization", "Bearer "
                + jwt.issue(17, "PASSENGER", java.time.Instant.now().plusSeconds(3600))))
                .andExpect(status().isForbidden());
        verify(drivers).state(42);
        verifyNoMoreInteractions(drivers);
    }
}

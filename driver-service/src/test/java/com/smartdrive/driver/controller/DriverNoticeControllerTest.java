package com.smartdrive.driver.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.common.api.auth.ServletAuthFilter;
import com.smartdrive.common.api.web.GlobalExceptionHandler;
import com.smartdrive.driver.mapper.OrderNoticeMapper;
import com.smartdrive.driver.service.DriverService;
import com.smartdrive.driver.service.OrderNoticeService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DriverNoticeControllerTest {
    @Test void scopesNoticesToJwtAndValidatesQuery() throws Exception {
        var mapper = mock(OrderNoticeMapper.class);
        var jwt = new JwtCodec("step13-test-secret-at-least-thirty-two-bytes", new ObjectMapper(), java.time.Clock.systemUTC());
        var mvc = MockMvcBuilders.standaloneSetup(new DriverController(mock(DriverService.class), new OrderNoticeService(mapper)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new ServletAuthFilter(jwt, new ObjectMapper())).build();
        String token = jwt.issue(42, "DRIVER", java.time.Instant.now().plusSeconds(3600));
        when(mapper.findNew(42, 100)).thenReturn(List.of(9L, 8L));
        mvc.perform(get("/api/drivers/me/notices").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value(9))
                .andExpect(jsonPath("$.data[1]").value(8));
        verify(mapper).findNew(42, 100);
        for (String query : new String[]{"?status=READ", "?limit=0", "?limit=101", "?limit=no"}) {
            mvc.perform(get("/api/drivers/me/notices" + query).header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/drivers/me/notices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/drivers/me/notices").header("Authorization", "Bearer "
                + jwt.issue(17, "PASSENGER", java.time.Instant.now().plusSeconds(3600)))).andExpect(status().isForbidden());
        verifyNoMoreInteractions(mapper);
    }
}

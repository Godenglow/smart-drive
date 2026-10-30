package com.smartdrive.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.gateway.filter.JwtGlobalFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class JwtGlobalFilterTest {
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");
    private final JwtCodec codec = new JwtCodec("test-secret-must-be-longer-than-thirty-two-bytes",
            new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
    private final JwtGlobalFilter filter = new JwtGlobalFilter(codec, new ObjectMapper());

    @Test
    void gatewayRejectsMissingExpiredAndForgedTokens() {
        assertEquals(HttpStatus.UNAUTHORIZED, call("/api/users/me", null).getResponse().getStatusCode());
        String valid = codec.issue(1, "PASSENGER", now.plusSeconds(60));
        int signatureStart = valid.lastIndexOf('.') + 1;
        String forged = valid.substring(0, signatureStart) + (valid.charAt(signatureStart) == 'A' ? 'B' : 'A')
                + valid.substring(signatureStart + 1);
        assertEquals(HttpStatus.UNAUTHORIZED, call("/api/users/me", forged).getResponse().getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, call("/api/users/me", valid + "x").getResponse().getStatusCode());
        JwtCodec expiredCodec = new JwtCodec("test-secret-must-be-longer-than-thirty-two-bytes",
                new ObjectMapper(), Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
        JwtGlobalFilter expiredFilter = new JwtGlobalFilter(expiredCodec, new ObjectMapper());
        assertEquals(HttpStatus.UNAUTHORIZED, call(expiredFilter, "/api/users/me", valid).getResponse().getStatusCode());
    }

    @Test
    void gatewayAuthorizesByRoleAndAllowsPublicLogin() {
        String passenger = codec.issue(1, "PASSENGER", now.plusSeconds(60));
        String driver = codec.issue(2, "DRIVER", now.plusSeconds(60));
        assertEquals(HttpStatus.FORBIDDEN, call("/api/drivers/me/notices", passenger).getResponse().getStatusCode());
        assertEquals(HttpStatus.OK, call("/api/drivers/me/notices", driver).getResponse().getStatusCode());
        assertEquals(HttpStatus.OK, call("/api/auth/login", null).getResponse().getStatusCode());
    }

    private MockServerWebExchange call(String path, String token) {
        return call(filter, path, token);
    }

    private MockServerWebExchange call(JwtGlobalFilter target, String path, String token) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = ignored -> {
            ignored.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };
        target.filter(exchange, chain).block();
        return exchange;
    }
}

package com.smartdrive.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.ApiError;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.RequestIds;
import com.smartdrive.common.api.auth.AuthPolicy;
import com.smartdrive.common.api.auth.JwtCodec;
import com.smartdrive.common.api.auth.Principal;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtGlobalFilter implements GlobalFilter, Ordered {
    private final JwtCodec codec;
    private final ObjectMapper mapper;

    public JwtGlobalFilter(JwtCodec codec, ObjectMapper mapper) {
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/") || AuthPolicy.publicPath(path)) return chain.filter(exchange);
        Principal principal;
        try {
            principal = codec.verify(AuthPolicy.bearer(exchange.getRequest().getHeaders().getFirst("Authorization")));
        } catch (IllegalArgumentException ex) {
            return reject(exchange, ErrorCode.UNAUTHORIZED);
        }
        if (!AuthPolicy.allowed(path, exchange.getRequest().getMethod().name(), principal.role())) {
            return reject(exchange, ErrorCode.FORBIDDEN);
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode code) {
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(code.httpStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String requestId = RequestIds.resolve(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        try {
            byte[] body = mapper.writeValueAsBytes(ApiError.of(code, code.message(), requestId));
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

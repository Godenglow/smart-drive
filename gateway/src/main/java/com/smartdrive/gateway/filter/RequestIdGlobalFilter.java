package com.smartdrive.gateway.filter;

import com.smartdrive.common.api.RequestIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 为每个进入网关的请求确定 requestId：客户端带了合法的 X-Request-Id 就透传，否则生成新的；
 * 写入下游请求头并回写响应头，让前后端与服务日志能对齐同一次调用。
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = RequestIds.resolve(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        log.info("--> {} {} requestId={}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), requestId);

        // 等下游响应头合并完成后再写，用 set 覆盖，避免与业务服务回写的同名头重复
        exchange.getResponse().beforeCommit(() -> Mono.fromRunnable(
                () -> exchange.getResponse().getHeaders().set(RequestIds.HEADER, requestId)));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(RequestIds.HEADER, requestId))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

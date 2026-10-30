package com.smartdrive.common.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdrive.common.api.ApiError;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.common.api.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ServletAuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = Principal.class.getName();
    private final JwtCodec codec;
    private final ObjectMapper mapper;

    public ServletAuthFilter(JwtCodec codec, ObjectMapper mapper) {
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || AuthPolicy.publicPath(path)) {
            chain.doFilter(request, response);
            return;
        }
        Principal principal;
        try {
            principal = codec.verify(AuthPolicy.bearer(request.getHeader("Authorization")));
        } catch (IllegalArgumentException ex) {
            reject(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        if (!AuthPolicy.allowed(path, request.getMethod(), principal.role())) {
            reject(response, ErrorCode.FORBIDDEN);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiError.of(code, code.message(), RequestIds.current()));
    }
}

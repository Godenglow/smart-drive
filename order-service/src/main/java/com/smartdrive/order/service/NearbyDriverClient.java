package com.smartdrive.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
public class NearbyDriverClient {
    private final RestClient client;
    private final String token;

    public NearbyDriverClient(@LoadBalanced RestClient.Builder builder,
            @Value("${smartdrive.internal-token:}") String token) {
        this.client = builder.build();
        this.token = token;
    }

    public boolean hasNearby(BigDecimal lng, BigDecimal lat) {
        if (token.isBlank()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        try {
            JsonNode response = client.get()
                    .uri("http://driver-service/internal/drivers/nearby?lng={lng}&lat={lat}", lng, lat)
                    .header("X-Internal-Token", token)
                    .retrieve().body(JsonNode.class);
            if (response == null || response.path("code").asInt(-1) != 0 || !response.path("data").isArray()) {
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            return !response.path("data").isEmpty();
        } catch (RestClientException ex) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public void requireEligible(long driverId, long orderId) {
        JsonNode response = eligibility(driverId, orderId);
        if (!response.path("data").path("notified").booleanValue()) throw new ApiException(ErrorCode.FORBIDDEN);
        if (!response.path("data").path("online").booleanValue()) throw new ApiException(ErrorCode.DRIVER_OFFLINE);
    }

    public boolean wasNotified(long driverId, long orderId) {
        return eligibility(driverId, orderId).path("data").path("notified").booleanValue();
    }

    private JsonNode eligibility(long driverId, long orderId) {
        if (token.isBlank()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        JsonNode response;
        try {
            response = client.get()
                    .uri("http://driver-service/internal/drivers/{driverId}/eligibility?orderId={orderId}",
                            driverId, orderId)
                    .header("X-Internal-Token", token)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (response == null || response.path("code").asInt(-1) != 0
                || !response.path("data").path("online").isBoolean()
                || !response.path("data").path("notified").isBoolean()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return response;
    }

    @Configuration
    static class ClientConfiguration {
        @Bean
        @LoadBalanced
        RestClient.Builder driverRestClientBuilder() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(3000);
            return RestClient.builder().requestFactory(factory);
        }
    }
}

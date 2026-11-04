package com.smartdrive.driver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class MatchingRetryConfiguration {
    @Bean
    public RetryTemplate matchingRetryTemplate(
            @Value("${smartdrive.matching.max-attempts:4}") int attempts,
            @Value("${smartdrive.matching.initial-interval-ms:1000}") long initial,
            @Value("${smartdrive.matching.max-interval-ms:5000}") long maximum) {
        if (attempts < 1 || attempts > 10 || initial < 1 || maximum < initial || maximum > 60000) {
            throw new IllegalArgumentException("Invalid matching retry limits");
        }
        return RetryTemplate.builder().maxAttempts(attempts)
                .exponentialBackoff(initial, 2, maximum).retryOn(RuntimeException.class).build();
    }
}

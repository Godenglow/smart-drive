package com.smartdrive.common.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtConfiguration {
    @Bean
    public JwtCodec jwtCodec(@Value("${JWT_SECRET:}") String secret, ObjectMapper mapper) {
        return new JwtCodec(secret, mapper, Clock.systemUTC());
    }
}

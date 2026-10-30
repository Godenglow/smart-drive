package com.smartdrive.common.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HS256 JWT shared by the gateway and all business services. */
public final class JwtCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    private final byte[] secret;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JwtCodec(String secret, ObjectMapper mapper, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        this.mapper = mapper;
        this.clock = clock;
    }

    public String issue(long userId, String role, Instant expiresAt) {
        if (userId <= 0 || !validRole(role) || !expiresAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("Invalid JWT claims");
        }
        try {
            byte[] body = mapper.writeValueAsBytes(new Claims(userId, role, expiresAt.getEpochSecond()));
            String content = HEADER + "." + ENCODER.encodeToString(body);
            return content + "." + ENCODER.encodeToString(sign(content));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not issue JWT", ex);
        }
    }

    public Principal verify(String token) {
        try {
            if (token == null || token.length() > 4096) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) throw new IllegalArgumentException();
            byte[] actual = DECODER.decode(parts[2]);
            if (actual.length != 32 || !ENCODER.encodeToString(actual).equals(parts[2]))
                throw new IllegalArgumentException();
            if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]), actual)) throw new IllegalArgumentException();
            JsonNode claims = mapper.readTree(DECODER.decode(parts[1]));
            JsonNode sub = claims.path("sub");
            JsonNode role = claims.path("role");
            JsonNode exp = claims.path("exp");
            if (!sub.isIntegralNumber() || !role.isTextual() || !exp.isIntegralNumber()
                    || sub.longValue() <= 0 || !validRole(role.textValue())
                    || exp.longValue() <= clock.instant().getEpochSecond()) throw new IllegalArgumentException();
            return new Principal(sub.longValue(), role.textValue());
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid or expired JWT");
        }
    }

    private byte[] sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.US_ASCII));
    }

    private boolean validRole(String role) {
        return "PASSENGER".equals(role) || "DRIVER".equals(role);
    }

    private record Claims(long sub, String role, long exp) {}
}

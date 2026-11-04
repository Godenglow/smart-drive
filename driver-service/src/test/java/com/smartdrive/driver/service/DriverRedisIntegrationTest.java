package com.smartdrive.driver.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.dto.ProfileView;
import com.smartdrive.driver.mapper.DriverProfileMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class DriverRedisIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "SMARTDRIVE_REDIS_INTEGRATION", matches = "true")
    void nearbyFiltersFarAndExpiredDriversBeforeFivePersonLimit() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "192.168.220.128"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        config.setPassword(System.getenv("REDIS_PASSWORD"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        DriverProfileMapper profiles = Mockito.mock(DriverProfileMapper.class);
        DriverService service = new DriverService(profiles, redis);
        long base = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        List<Long> ids = new ArrayList<>();
        LocationRequest center = point("10", "10");
        try {
            for (int i = 0; i < 6; i++) {
                long id = base + i;
                ids.add(id);
                when(profiles.find(id)).thenReturn(new ProfileView(id, "测试司机", LocalDateTime.now()));
                service.online(id, point("10.000" + i, "10"));
            }
            long expired = base + 6;
            long far = base + 7;
            ids.add(expired);
            ids.add(far);
            when(profiles.find(expired)).thenReturn(new ProfileView(expired, "过期司机", LocalDateTime.now()));
            when(profiles.find(far)).thenReturn(new ProfileView(far, "远处司机", LocalDateTime.now()));
            service.online(expired, center);
            service.online(far, point("11", "11"));
            assertTrue(service.state(expired).online());
            redis.delete(DriverService.ONLINE_PREFIX + expired);
            assertFalse(service.state(expired).online());

            List<Long> nearby = service.nearby(center);
            assertEquals(5, nearby.size());
            assertFalse(nearby.contains(expired));
            assertFalse(nearby.contains(far));
            assertTrue(nearby.stream().allMatch(id -> id >= base && id < base + 6));
            assertEquals(null, redis.opsForZSet().score(DriverService.GEO_KEY, Long.toString(expired)));
            assertTrue(redis.getExpire(DriverService.ONLINE_PREFIX + base) > 0);
        } finally {
            for (long id : ids) service.offline(id);
            factory.destroy();
        }
    }

    private static LocationRequest point(String lng, String lat) {
        return new LocationRequest(new BigDecimal(lng), new BigDecimal(lat));
    }
}

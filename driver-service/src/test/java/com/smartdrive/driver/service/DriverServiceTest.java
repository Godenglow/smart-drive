package com.smartdrive.driver.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.driver.dto.LocationRequest;
import com.smartdrive.driver.dto.ProfileView;
import com.smartdrive.driver.mapper.DriverProfileMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class DriverServiceTest {
    private final DriverProfileMapper profiles = org.mockito.Mockito.mock(DriverProfileMapper.class);
    private final StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
    private final DriverService service = new DriverService(profiles, redis);
    private final LocationRequest point = new LocationRequest(new BigDecimal("121.4737"), new BigDecimal("31.2304"));

    @BeforeEach
    void profileExists() {
        when(profiles.find(42L)).thenReturn(new ProfileView(42L, "司机", LocalDateTime.now()));
    }

    @Test
    void readsCurrentProfileAndMarkerWithoutRenewingIt() {
        when(redis.hasKey("smartdrive:driver:online:42")).thenReturn(true, false);
        var online = service.state(42L);
        assertEquals("司机", online.profile().displayName());
        assertTrue(online.online());
        assertTrue(!service.state(42L).online());
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.times(2)).hasKey("smartdrive:driver:online:42");
        org.mockito.Mockito.verifyNoMoreInteractions(redis);
        assertEquals(null, service.state(99L).profile());
    }

    @Test
    void onlineWritesGeoAndExpiringMarkerTogether() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), anyString(), anyString(), anyString())).thenReturn(1L);
        service.online(42L, point);
        ArgumentCaptor<DefaultRedisScript<Long>> script = scriptCaptor();
        verify(redis).execute(script.capture(), eq(List.of(DriverService.GEO_KEY, "smartdrive:driver:online:42")),
                eq("42"), eq("121.4737"), eq("31.2304"));
        assertEquals(true, script.getValue().getScriptAsString().contains("'EX', 120"));
        assertEquals(true, script.getValue().getScriptAsString().contains("GEOADD"));
    }

    @Test
    void expiredMarkerRejectsLocationRefresh() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), anyString(), anyString(), anyString())).thenReturn(0L);
        ApiException ex = assertThrows(ApiException.class, () -> service.updateLocation(42L, point));
        assertEquals(ErrorCode.DRIVER_OFFLINE, ex.errorCode());
    }

    @Test
    void locationHeartbeatRefreshesTwoMinuteMarkerAndPosition() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), anyString(), anyString(), anyString())).thenReturn(1L);
        service.updateLocation(42L, point);
        ArgumentCaptor<DefaultRedisScript<Long>> script = scriptCaptor();
        verify(redis).execute(script.capture(), eq(List.of(DriverService.GEO_KEY, "smartdrive:driver:online:42")),
                eq("42"), eq("121.4737"), eq("31.2304"));
        assertTrue(script.getValue().getScriptAsString().contains("'EX', 120"));
        assertTrue(script.getValue().getScriptAsString().contains("GEOADD"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void nearbyQueriesFiveKilometersAndChecksMarkersBeforeLimiting() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), anyString(), anyString()))
                .thenReturn(List.of("42", "43"));
        assertEquals(List.of(42L, 43L), service.nearby(point));
        ArgumentCaptor<DefaultRedisScript<List>> script = ArgumentCaptor.forClass((Class) DefaultRedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of(DriverService.GEO_KEY)), eq("121.4737"), eq("31.2304"));
        String source = script.getValue().getScriptAsString();
        assertTrue(source.contains("'BYRADIUS', 5, 'km', 'ASC'"));
        assertTrue(source.indexOf("'EXISTS'") < source.indexOf("#result == 5"));
        assertTrue(source.contains("'ZREM'"));
    }

    @Test
    void offlineRemovesMarkerAndGeoMember() {
        when(redis.execute(any(DefaultRedisScript.class), any(List.class), anyString(), anyString(), anyString())).thenReturn(1L);
        service.offline(42L);
        ArgumentCaptor<DefaultRedisScript<Long>> script = scriptCaptor();
        verify(redis).execute(script.capture(), eq(List.of(DriverService.GEO_KEY, "smartdrive:driver:online:42")),
                eq("42"), eq(""), eq(""));
        assertEquals(true, script.getValue().getScriptAsString().contains("ZREM"));
        assertEquals(true, script.getValue().getScriptAsString().contains("DEL"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor() {
        return ArgumentCaptor.forClass((Class) DefaultRedisScript.class);
    }
}

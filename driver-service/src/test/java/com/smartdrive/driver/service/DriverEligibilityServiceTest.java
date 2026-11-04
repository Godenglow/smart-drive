package com.smartdrive.driver.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartdrive.driver.dto.EligibilityView;
import com.smartdrive.driver.mapper.OrderNoticeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class DriverEligibilityServiceTest {
    @Test void noticeAndLiveOnlineMarkerAreBothRequired() {
        OrderNoticeMapper notices = mock(OrderNoticeMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        DriverEligibilityService service = new DriverEligibilityService(notices, redis);
        when(notices.wasNotified(8, 42)).thenReturn(true);
        when(redis.hasKey(DriverService.ONLINE_PREFIX + 42)).thenReturn(true);
        when(redis.hasKey(DriverService.ONLINE_PREFIX + 43)).thenReturn(true);
        assertEquals(new EligibilityView(true, true), service.check(42, 8));
        when(redis.hasKey(DriverService.ONLINE_PREFIX + 42)).thenReturn(false);
        assertEquals(new EligibilityView(false, true), service.check(42, 8));
        when(redis.hasKey(DriverService.ONLINE_PREFIX + 42)).thenReturn(true);
        assertEquals(new EligibilityView(true, false), service.check(43, 8));
        assertEquals(new EligibilityView(true, false), service.check(42, 9));
    }

    @Test void unavailableOnlineStoreDoesNotGrantEligibility() {
        OrderNoticeMapper notices = mock(OrderNoticeMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey(DriverService.ONLINE_PREFIX + 42)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> new DriverEligibilityService(notices, redis).check(42, 8));
    }
}

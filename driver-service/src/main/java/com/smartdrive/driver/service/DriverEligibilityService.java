package com.smartdrive.driver.service;

import com.smartdrive.driver.dto.EligibilityView;
import com.smartdrive.driver.mapper.OrderNoticeMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DriverEligibilityService {
    private final OrderNoticeMapper notices;
    private final StringRedisTemplate redis;

    public DriverEligibilityService(OrderNoticeMapper notices, StringRedisTemplate redis) {
        this.notices = notices;
        this.redis = redis;
    }

    public EligibilityView check(long driverId, long orderId) {
        boolean notified = notices.wasNotified(orderId, driverId);
        Boolean online = redis.hasKey(DriverService.ONLINE_PREFIX + driverId);
        if (online == null) throw new IllegalStateException("Redis online query returned no result");
        return new EligibilityView(online, notified);
    }
}

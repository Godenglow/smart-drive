package com.smartdrive.driver.service;

import com.smartdrive.common.api.ApiException;
import com.smartdrive.common.api.ErrorCode;
import com.smartdrive.driver.mapper.OrderNoticeMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderNoticeService {
    private final OrderNoticeMapper notices;

    public OrderNoticeService(OrderNoticeMapper notices) { this.notices = notices; }

    public List<Long> list(long driverId, String status, int limit) {
        if (!"NEW".equals(status) || limit < 1 || limit > 100) throw new ApiException(ErrorCode.INVALID_PARAM);
        return notices.findNew(driverId, limit);
    }
}

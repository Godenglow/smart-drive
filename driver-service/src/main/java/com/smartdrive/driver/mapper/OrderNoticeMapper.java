package com.smartdrive.driver.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import java.util.List;

@Mapper
public interface OrderNoticeMapper {
    @Insert("INSERT INTO processed_order_event(order_id, event_id) VALUES(#{orderId}, #{eventId})")
    int claim(@Param("orderId") long orderId, @Param("eventId") String eventId);

    @Select("SELECT event_id FROM processed_order_event WHERE order_id = #{orderId}")
    String processedEvent(long orderId);

    @Insert("""
            INSERT INTO order_notice(order_id, driver_id) VALUES(#{orderId}, #{driverId})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertNotice(@Param("orderId") long orderId, @Param("driverId") long driverId);

    @Select("""
            SELECT order_id FROM order_notice WHERE driver_id = #{driverId} AND status = 'NEW'
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<Long> findNew(@Param("driverId") long driverId, @Param("limit") int limit);

    @Select("SELECT EXISTS(SELECT 1 FROM order_notice WHERE order_id = #{orderId} AND driver_id = #{driverId})")
    boolean wasNotified(@Param("orderId") long orderId, @Param("driverId") long driverId);
}

package com.smartdrive.order.mapper;

import com.smartdrive.order.entity.RideOrder;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {
    @Insert("""
            INSERT INTO ride_order(request_id, passenger_id, pickup_name, pickup_lng, pickup_lat,
                dropoff_name, dropoff_lng, dropoff_lat, amount, status)
            VALUES(#{requestId}, #{passengerId}, #{pickupName}, #{pickupLng}, #{pickupLat},
                #{dropoffName}, #{dropoffLng}, #{dropoffLat}, #{amount}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(RideOrder order);

    @Select("SELECT * FROM ride_order WHERE id = #{id}")
    RideOrder findById(long id);

    @Select("""
            SELECT * FROM ride_order WHERE
            (#{role} = 'PASSENGER' AND passenger_id = #{userId}) OR
            (#{role} = 'DRIVER' AND driver_id = #{userId})
            ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<RideOrder> findMine(@Param("userId") long userId, @Param("role") String role,
                             @Param("size") int size, @Param("offset") long offset);

    @Select("""
            SELECT COUNT(*) FROM ride_order WHERE
            (#{role} = 'PASSENGER' AND passenger_id = #{userId}) OR
            (#{role} = 'DRIVER' AND driver_id = #{userId})
            """)
    long countMine(@Param("userId") long userId, @Param("role") String role);
}

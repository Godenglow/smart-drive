package com.smartdrive.order.mapper;

import com.smartdrive.order.entity.RideOrder;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

    @Select("SELECT * FROM ride_order WHERE passenger_id = #{passengerId} AND request_id = #{requestId}")
    RideOrder findByRequest(@Param("passengerId") long passengerId, @Param("requestId") String requestId);

    @Update("""
            UPDATE ride_order SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND passenger_id = #{passengerId} AND status = 'WAITING'
            """)
    int cancelWaiting(@Param("id") long id, @Param("passengerId") long passengerId);

    @Insert("INSERT INTO driver_active_order(driver_id, order_id) VALUES(#{driverId}, #{orderId})")
    int occupyDriver(@Param("driverId") long driverId, @Param("orderId") long orderId);

    @Select("SELECT order_id FROM driver_active_order WHERE driver_id = #{driverId}")
    Long findActiveOrder(long driverId);

    @Update("""
            UPDATE ride_order SET driver_id = #{driverId}, status = 'ACCEPTED', updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{orderId} AND status = 'WAITING' AND driver_id IS NULL
            """)
    int acceptWaiting(@Param("orderId") long orderId, @Param("driverId") long driverId);

    @Update("""
            UPDATE ride_order SET status = 'IN_SERVICE', updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{orderId} AND driver_id = #{driverId} AND status = 'ACCEPTED'
            """)
    int startAccepted(@Param("orderId") long orderId, @Param("driverId") long driverId);

    @Update("""
            UPDATE ride_order SET status = 'FINISHED', updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{orderId} AND driver_id = #{driverId} AND status = 'IN_SERVICE'
            """)
    int finishInService(@Param("orderId") long orderId, @Param("driverId") long driverId);

    @Delete("DELETE FROM driver_active_order WHERE driver_id = #{driverId} AND order_id = #{orderId}")
    int releaseDriver(@Param("driverId") long driverId, @Param("orderId") long orderId);

    @Update("""
            UPDATE ride_order SET status = 'PAID', updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{orderId} AND passenger_id = #{passengerId} AND status = 'FINISHED'
            """)
    int payFinished(@Param("orderId") long orderId, @Param("passengerId") long passengerId);

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

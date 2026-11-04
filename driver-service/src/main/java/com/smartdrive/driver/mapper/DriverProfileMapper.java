package com.smartdrive.driver.mapper;

import com.smartdrive.driver.dto.ProfileView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface DriverProfileMapper {
    @Select("SELECT user_id, display_name, created_at FROM driver_profile WHERE user_id = #{userId}")
    ProfileView find(long userId);

    @Insert("INSERT INTO driver_profile(user_id, display_name) VALUES(#{userId}, #{displayName}) "
            + "ON DUPLICATE KEY UPDATE display_name = #{displayName}")
    int upsert(@Param("userId") long userId, @Param("displayName") String displayName);
}

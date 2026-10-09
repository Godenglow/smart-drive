package com.smartdrive.account.mapper;

import com.smartdrive.account.entity.Account;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

/** account 表的数据访问；注册与账户查询共用 */
@Mapper
public interface AccountMapper {

    @Select("SELECT id, phone, password_hash, role, created_at FROM account WHERE phone = #{phone}")
    Account selectByPhone(String phone);

    @Select("SELECT id, phone, password_hash, role, created_at FROM account WHERE id = #{id}")
    Account selectById(long id);

    @Insert("INSERT INTO account(phone, password_hash, role) VALUES(#{phone}, #{passwordHash}, #{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Account account);
}

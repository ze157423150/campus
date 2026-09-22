package com.campus.ticket.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT COUNT(*)
            FROM campus_user
            WHERE id = #{userId}
            """)
    int countById(@Param("userId") Long userId);
}
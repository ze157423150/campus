package com.campus.ticket.mapper;

import com.campus.ticket.entity.Venue;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface VenueMapper
{
    String COLUMNS = "id, name, address, capacity, description, status, create_time, update_time";
    String FILTER = """
            FROM venue
            <where>
                <if test="status != null">status = #{status}</if>
                <if test="keyword != null">AND (LOCATE(#{keyword}, name) > 0 OR LOCATE(#{keyword}, address) > 0)</if>
            </where>
            """;

    @Select("SELECT " + COLUMNS + " FROM venue WHERE id = #{id} AND status = 'OPEN'")
    Venue findOpenById(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM venue WHERE id = #{id}")
    Venue findManagementById(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM venue WHERE id = #{id} FOR UPDATE")
    Venue lockById(@Param("id") Long id);

    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    long count(@Param("keyword") String keyword, @Param("status") String status);

    @Select("<script>SELECT " + COLUMNS + " " + FILTER + " ORDER BY id DESC LIMIT #{pageSize} OFFSET #{offset}</script>")
    List<Venue> findPage(@Param("keyword") String keyword, @Param("status") String status, @Param("offset") long offset, @Param("pageSize") int pageSize);

    @Insert("""
            INSERT INTO venue(name, address, capacity, description, status)
            VALUES(#{name}, #{address}, #{capacity}, #{description}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Venue venue);

    @Update("""
            UPDATE venue SET name = #{name}, address = #{address}, capacity = #{capacity},
                description = #{description}, status = #{status}, update_time = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int update(Venue venue);
}

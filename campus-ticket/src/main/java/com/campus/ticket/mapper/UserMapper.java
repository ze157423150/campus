package com.campus.ticket.mapper;

import com.campus.ticket.entity.CampusUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {

    @Select("""
            SELECT COUNT(*)
            FROM campus_user
            WHERE id = #{userId}
            """)
    int countById(@Param("userId") Long userId);

    @Select("""
        SELECT id, student_no, name, password_hash, role
        FROM campus_user
        WHERE student_no = #{studentNo}
        """)
    CampusUser findByStudentNo(@Param("studentNo") String studentNo);

    @Select("""
        SELECT id, student_no, name, role
        FROM campus_user
        WHERE id = #{userId}
        """)
    CampusUser findById(@Param("userId") Long userId);

    @Select("SELECT id, student_no, name, password_hash, role FROM campus_user WHERE id = #{userId}")
    CampusUser findCredentialsById(@Param("userId") Long userId);

    @Select("SELECT id, student_no, name, password_hash, role FROM campus_user WHERE id = #{userId} FOR UPDATE")
    CampusUser findCredentialsByIdForUpdate(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO campus_user (student_no, name, password_hash, role)
            VALUES (#{studentNo}, #{name}, #{passwordHash}, 'STUDENT')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertStudent(CampusUser user);

    @Update("UPDATE campus_user SET password_hash = #{passwordHash} WHERE id = #{userId}")
    int updatePassword(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Update("UPDATE campus_user SET name = #{name} WHERE id = #{userId}")
    int updateName(@Param("userId") Long userId, @Param("name") String name);
}

package com.campusbooking.registration.mapper;

import com.campusbooking.registration.RegistrationMessage;
import com.campusbooking.registration.RegistrationViews.*;
import com.campusbooking.registration.RegistrationViews.Result;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface RegistrationMapper {
    @Select("SELECT request_id, user_id, session_id, status, registration_id, failure_code, completed_at "
            + "FROM registration_result WHERE request_id=#{id}")
    Result result(String id);

    @Select("SELECT id, request_id, user_id, session_id, created_at FROM registration "
            + "WHERE user_id=#{userId} AND session_id=#{sessionId}")
    Registration existing(@Param("userId") long userId, @Param("sessionId") long sessionId);

    @Update("UPDATE activity_session SET remaining_capacity=remaining_capacity-1 "
            + "WHERE id=#{id} AND remaining_capacity>0 AND status='PUBLISHED' AND end_at>CURRENT_TIMESTAMP(3)")
    int takeCapacity(long id);

    @Insert("INSERT INTO registration(request_id,user_id,session_id) VALUES(#{requestId},#{userId},#{sessionId})")
    int insertRegistration(RegistrationMessage message);

    @Insert("INSERT INTO registration_result(request_id,user_id,session_id,status,registration_id,failure_code,completed_at) "
            + "VALUES(#{requestId},#{userId},#{sessionId},#{status},#{registrationId},#{failureCode},#{completedAt})")
    int insertResult(Result result);

    @Select("SELECT COUNT(*) FROM registration WHERE user_id=#{userId}")
    long count(long userId);

    @Select("SELECT id, request_id, user_id, session_id, created_at FROM registration "
            + "WHERE user_id=#{userId} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Registration> mine(@Param("userId") long userId, @Param("size") int size, @Param("offset") long offset);
}

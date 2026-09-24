package com.campusbooking.participation.mapper;

import com.campusbooking.participation.ParticipationViews.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

@Mapper
public interface ParticipationMapper {
    @Select("SELECT id FROM registration WHERE id=#{id} FOR UPDATE")
    Long lockRegistration(long id);

    @Select("SELECT r.id registration_id,r.user_id,r.session_id,a.organizer_id,s.start_at,s.end_at "
            + "FROM registration r JOIN registration_result t ON t.request_id=r.request_id "
            + "AND t.registration_id=r.id AND t.user_id=r.user_id AND t.session_id=r.session_id AND t.status='SUCCESS' "
            + "JOIN activity_session s ON s.id=r.session_id JOIN activity a ON a.id=s.activity_id "
            + "WHERE r.id=#{id} AND s.status='PUBLISHED'")
    Eligible eligible(long id);

    @Select("SELECT registration_id,code FROM participation_credential WHERE registration_id=#{id}")
    Credential credential(long id);

    @Select("SELECT registration_id FROM participation_credential WHERE code=#{code}")
    Long registrationId(String code);

    @Insert("INSERT INTO participation_credential(registration_id,code) VALUES(#{registrationId},#{code})")
    int insertCredential(Credential credential);

    @Insert("INSERT INTO participation_record(registration_id,checked_by,checked_at) VALUES(#{id},#{organizer},#{now})")
    int insertAttendance(@Param("id") long id, @Param("organizer") long organizer, @Param("now") Instant now);

    String ATTENDANCE = "SELECT p.registration_id,r.user_id,u.nickname,r.session_id,a.title activity_title,p.checked_by,p.checked_at "
            + "FROM participation_record p JOIN registration r ON r.id=p.registration_id "
            + "JOIN account_user u ON u.id=r.user_id JOIN activity_session s ON s.id=r.session_id "
            + "JOIN activity a ON a.id=s.activity_id ";

    @Select(ATTENDANCE + "WHERE p.registration_id=#{id}")
    Attendance attendance(long id);

    @Select("SELECT a.organizer_id FROM activity_session s JOIN activity a ON a.id=s.activity_id WHERE s.id=#{id}")
    Long organizer(long id);

    @Select("SELECT COUNT(*) FROM participation_record p JOIN registration r ON r.id=p.registration_id WHERE r.user_id=#{id}")
    long countMine(long id);

    @Select(ATTENDANCE + "WHERE r.user_id=#{id} ORDER BY p.checked_at DESC,p.registration_id DESC LIMIT #{size} OFFSET #{offset}")
    List<Attendance> mine(@Param("id") long id, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM participation_record p JOIN registration r ON r.id=p.registration_id WHERE r.session_id=#{id}")
    long countSession(long id);

    @Select(ATTENDANCE + "WHERE r.session_id=#{id} ORDER BY p.checked_at DESC,p.registration_id DESC LIMIT #{size} OFFSET #{offset}")
    List<Attendance> attendees(@Param("id") long id, @Param("size") int size, @Param("offset") long offset);
}

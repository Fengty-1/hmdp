package com.campusbooking.participation;

import com.campusbooking.account.auth.UserContext;
import com.campusbooking.account.model.Role;
import com.campusbooking.activity.dto.ActivityViews.Page;
import com.campusbooking.common.ApiException;
import com.campusbooking.participation.ParticipationViews.*;
import com.campusbooking.participation.mapper.ParticipationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

@Service
public class ParticipationService {
    private static final Logger log = LoggerFactory.getLogger(ParticipationService.class);
    private final ParticipationMapper mapper;
    private final Clock clock;

    public ParticipationService(ParticipationMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Credential issue(long registrationId) {
        long user = requireRole(Role.STUDENT);
        positive(registrationId);
        Eligible registration = mapper.eligible(registrationId);
        if (registration == null || registration.userId() != user) throw notFound();
        // 同一报名串行领取；已有 Stage 3 报名也能领取，不需回填随机码。
        mapper.lockRegistration(registrationId);
        Credential existing = mapper.credential(registrationId);
        if (existing != null) return existing;
        Credential credential = new Credential(registrationId, UUID.randomUUID().toString().replace("-", ""));
        mapper.insertCredential(credential);
        return credential;
    }

    @Transactional(timeout = 10, isolation = Isolation.READ_COMMITTED)
    public Attendance verify(long sessionId, String code) {
        long organizer = requireRole(Role.ORGANIZER);
        positive(sessionId);
        ownSession(sessionId, organizer);
        if (code == null || !code.matches("[0-9a-f]{32}"))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "凭证应为 32 位小写十六进制字符");
        Long id = mapper.registrationId(code);
        if (id == null) throw notFound();
        mapper.lockRegistration(id);
        Eligible registration = mapper.eligible(id);
        if (registration == null || registration.sessionId() != sessionId) throw notFound();
        if (registration.organizerId() != organizer) throw forbidden();
        var now = clock.instant();
        if (now.isBefore(registration.startAt()) || !now.isBefore(registration.endAt()))
            throw error(HttpStatus.CONFLICT, "OUTSIDE_CHECK_IN_WINDOW", "仅可在场次开始至结束前核验");
        Attendance existing = mapper.attendance(id);
        if (existing != null) return existing;
        mapper.insertAttendance(id, organizer, now);
        log.info("Participation verified registrationId={} sessionId={} organizerId={}", id, sessionId, organizer);
        return mapper.attendance(id);
    }

    public Page<Attendance> mine(int page, int size) {
        long user = requireRole(Role.STUDENT);
        validatePage(page, size);
        return new Page<>(page, size, mapper.countMine(user), mapper.mine(user, size, ((long) page - 1) * size));
    }

    public Page<Attendance> attendees(long sessionId, int page, int size) {
        long organizer = requireRole(Role.ORGANIZER);
        positive(sessionId);
        validatePage(page, size);
        ownSession(sessionId, organizer);
        return new Page<>(page, size, mapper.countSession(sessionId), mapper.attendees(sessionId, size, ((long) page - 1) * size));
    }

    private void ownSession(long id, long organizer) {
        Long owner = mapper.organizer(id);
        if (owner == null) throw notFound();
        if (owner != organizer) throw forbidden();
    }
    private long requireRole(Role role) {
        var user = UserContext.require();
        if (user.role() != role) throw forbidden();
        return user.userId();
    }
    private void positive(long id) {
        if (id <= 0) throw error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "编号需大于零");
    }
    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 50)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "页码需大于零，每页 1～50 条");
    }
    private ApiException forbidden() { return error(HttpStatus.FORBIDDEN, "PARTICIPATION_FORBIDDEN", "无权执行此操作"); }
    private ApiException notFound() { return error(HttpStatus.NOT_FOUND, "PARTICIPATION_NOT_FOUND", "未找到可用的报名、凭证或场次"); }
    private ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}

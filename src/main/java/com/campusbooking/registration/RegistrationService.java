package com.campusbooking.registration;

import com.campusbooking.account.auth.UserContext;
import com.campusbooking.account.model.Role;
import com.campusbooking.activity.dto.ActivityViews.Page;
import com.campusbooking.common.ApiException;
import com.campusbooking.registration.RegistrationViews.*;
import com.campusbooking.registration.mapper.RegistrationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private final RequestIdGenerator ids;
    private final RegistrationRedisStore redis;
    private final RegistrationPublisher publisher;
    private final RegistrationMapper registrations;

    public RegistrationService(RequestIdGenerator ids, RegistrationRedisStore redis,
                               RegistrationPublisher publisher, RegistrationMapper registrations) {
        this.ids = ids;
        this.redis = redis;
        this.publisher = publisher;
        this.registrations = registrations;
    }

    public Accepted submit(long sessionId) {
        long userId = student();
        if (sessionId <= 0) throw error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "场次 ID 必须大于零");
        String candidate = ids.next();
        String decision = redis.accept(sessionId, userId, candidate);
        if (decision == null) throw error(HttpStatus.SERVICE_UNAVAILABLE, "ACCEPTANCE_UNKNOWN", "受理状态暂不明确");
        if (decision.startsWith("EXISTING:")) return pending(decision.substring(9));
        if (!decision.startsWith("ACCEPTED:")) {
            String message = switch (decision) {
                case "FULL" -> "场次名额已满";
                case "NOT_OPEN" -> "报名尚未开放";
                case "CLOSED" -> "报名已结束";
                default -> "场次报名运行配置暂不可用";
            };
            throw error(decision.startsWith("RUNTIME") ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT,
                    decision, message);
        }
        String[] parts = decision.split(":");
        String requestId = parts[1];
        log.info("Registration accepted requestId={} sessionId={} userId={}", requestId, sessionId, userId);
        try {
            publisher.send(new RegistrationMessage(requestId, userId, sessionId, Long.parseLong(parts[2])));
        } catch (RuntimeException exception) {
            log.warn("Registration send unconfirmed requestId={} type={}", requestId, exception.getClass().getSimpleName());
            return new Accepted(requestId, "PENDING", "请求已受理，消息投递暂未确认，请保留该 requestId 查询；无需重新提交");
        }
        return pending(requestId);
    }

    public Result result(String requestId) {
        long userId = student();
        validateId(requestId);
        try {
            Result result = registrations.result(requestId);
            if (result != null) {
                if (result.userId() != userId) throw notFound();
                return result;
            }
            var pending = redis.pending(requestId);
            if (pending.isEmpty() || !Long.toString(userId).equals(pending.get("userId"))) throw notFound();
            return new Result(requestId, userId, Long.parseLong((String) pending.get("sessionId")),
                    "PENDING", null, null, null);
        } catch (DataAccessException exception) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "RESULT_UNAVAILABLE", "查询服务暂不可用，请保留原 requestId 稍后查询");
        }
    }

    public Page<Registration> mine(int page, int size) {
        long userId = student();
        if (page < 1 || size < 1 || size > 50)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "页码需大于零，每页 1～50 条");
        return new Page<>(page, size, registrations.count(userId),
                registrations.mine(userId, size, ((long) page - 1) * size));
    }

    private Accepted pending(String id) {
        return new Accepted(id, "PENDING", "请求已受理，尚未获得最终报名结果，请保留 requestId 查询");
    }
    private long student() {
        var user = UserContext.require();
        if (user.role() != Role.STUDENT) throw error(HttpStatus.FORBIDDEN, "STUDENT_REQUIRED", "仅学生可以报名或查询自己的报名");
        return user.userId();
    }
    private void validateId(String id) {
        try {
            if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "requestId 格式不正确");
        }
    }
    private ApiException notFound() { return error(HttpStatus.NOT_FOUND, "RESULT_NOT_FOUND", "未查询到结果"); }
    private ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}

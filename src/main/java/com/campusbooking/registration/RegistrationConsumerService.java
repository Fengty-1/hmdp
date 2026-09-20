package com.campusbooking.registration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusbooking.activity.mapper.SessionMapper;
import com.campusbooking.activity.model.ActivitySession;
import com.campusbooking.registration.mapper.RegistrationMapper;
import com.campusbooking.registration.RegistrationViews.Result;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;

@Service
public class RegistrationConsumerService {
    private final RegistrationMapper registrations;
    private final SessionMapper sessions;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public RegistrationConsumerService(RegistrationMapper registrations, SessionMapper sessions,
                                       Clock clock, PlatformTransactionManager manager) {
        this.registrations = registrations;
        this.sessions = sessions;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result consume(RegistrationMessage message) {
        message.validate();
        try {
            return transaction.execute(status -> process(message));
        } catch (DuplicateKeyException conflict) {
            // execute 已完成回滚，再从已提交事实判定；技术性冲突不能伪装成 FAILED。
            return transaction.execute(status -> {
                Result existing = registrations.result(message.requestId());
                if (existing != null) return owned(existing, message);
                if (registrations.existing(message.userId(), message.sessionId()) != null)
                    return complete(message, null, "ALREADY_REGISTERED");
                throw conflict;
            });
        }
    }

    private Result process(RegistrationMessage message) {
        Result existing = registrations.result(message.requestId());
        if (existing != null) return owned(existing, message);
        // 同一场次的容量写入本来就需排他行锁；提前获取后重查终态，避免重复消息竞态。
        ActivitySession session = sessions.selectOne(new LambdaQueryWrapper<ActivitySession>()
                .eq(ActivitySession::getId, message.sessionId()).last("FOR UPDATE"));
        if (session == null) throw new IllegalStateException("Message session missing");
        existing = registrations.result(message.requestId());
        if (existing != null) return owned(existing, message);
        if (!clock.instant().isBefore(session.getEndAt())) return complete(message, null, "SESSION_ENDED");
        if (!"PUBLISHED".equals(session.getStatus())) return complete(message, null, "SESSION_NOT_PUBLISHED");
        Instant accepted = Instant.ofEpochMilli(message.acceptedAt());
        if (accepted.isBefore(session.getRegistrationStartAt()) || !accepted.isBefore(session.getRegistrationEndAt()))
            return complete(message, null, "OUTSIDE_REGISTRATION_WINDOW");
        if (registrations.existing(message.userId(), message.sessionId()) != null)
            return complete(message, null, "ALREADY_REGISTERED");
        if (session.getRemainingCapacity() <= 0) return complete(message, null, "CAPACITY_EXHAUSTED");
        // 已持有行锁且容量充足；若条件更新仍未命中，说明数据库时间已到场次截止。
        if (registrations.takeCapacity(message.sessionId()) == 0)
            return complete(message, null, "SESSION_ENDED");
        registrations.insertRegistration(message);
        long id = registrations.existing(message.userId(), message.sessionId()).id();
        return complete(message, id, null);
    }

    private Result complete(RegistrationMessage message, Long registrationId, String failure) {
        Result result = new Result(message.requestId(), message.userId(), message.sessionId(),
                failure == null ? "SUCCESS" : "FAILED", registrationId, failure, clock.instant());
        registrations.insertResult(result);
        return result;
    }

    private Result owned(Result result, RegistrationMessage message) {
        if (result.userId() != message.userId() || result.sessionId() != message.sessionId())
            throw new IllegalArgumentException("requestId ownership mismatch");
        return result;
    }
}

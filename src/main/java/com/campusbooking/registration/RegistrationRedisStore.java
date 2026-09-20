package com.campusbooking.registration;

import com.campusbooking.activity.SessionRuntimeStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class RegistrationRedisStore {
    private static final DefaultRedisScript<String> ACCEPT = new DefaultRedisScript<>();
    static {
        ACCEPT.setLocation(new ClassPathResource("redis/accept-registration.lua"));
        ACCEPT.setResultType(String.class);
    }
    private final StringRedisTemplate redis;
    public RegistrationRedisStore(StringRedisTemplate redis) { this.redis = redis; }
    public static String requestKey(String id) { return "registration:request:" + id; }

    public String accept(long sessionId, long userId, String requestId) {
        return redis.execute(ACCEPT, List.of(SessionRuntimeStore.runtimeKey(sessionId),
                SessionRuntimeStore.usersKey(sessionId), requestKey(requestId)),
                Long.toString(sessionId), Long.toString(userId), requestId);
    }

    public Map<Object, Object> pending(String requestId) {
        return redis.opsForHash().entries(requestKey(requestId));
    }
}

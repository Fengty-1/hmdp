package com.campusbooking.activity;

import com.campusbooking.activity.model.ActivitySession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SessionRuntimeStore {
    private static final DefaultRedisScript<Long> INITIALIZE = script();
    private final StringRedisTemplate redis;

    public SessionRuntimeStore(StringRedisTemplate redis) { this.redis = redis; }

    public static String runtimeKey(long id) { return "registration:session:" + id + ":runtime"; }
    public static String usersKey(long id) { return "registration:session:" + id + ":users"; }

    private static DefaultRedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/initialize-session.lua"));
        script.setResultType(Long.class);
        return script;
    }

    public boolean initialize(ActivitySession session) {
        Long result = redis.execute(INITIALIZE, List.of(runtimeKey(session.getId()), usersKey(session.getId())),
                session.getId().toString(), session.getActivityId().toString(), session.getCapacity().toString(),
                String.valueOf(session.getRegistrationStartAt().toEpochMilli()),
                String.valueOf(session.getRegistrationEndAt().toEpochMilli()),
                String.valueOf(session.getStartAt().toEpochMilli()),
                String.valueOf(session.getEndAt().toEpochMilli()),
                String.valueOf(session.getRuntimeExpireAt().toEpochMilli()));
        // 1 = 新初始化，0 = 完整配置一致且已有运行状态；都不覆盖库存。
        return result != null && (result == 1 || result == 0);
    }
}

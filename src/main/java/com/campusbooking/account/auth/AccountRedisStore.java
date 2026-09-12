package com.campusbooking.account.auth;

import com.campusbooking.account.AccountProperties;
import com.campusbooking.account.model.Role;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class AccountRedisStore {
    private static final DefaultRedisScript<Long> ISSUE_CODE = script("redis/issue-code.lua");
    private static final DefaultRedisScript<Long> CONSUME_CODE = script("redis/consume-code.lua");
    private final StringRedisTemplate redis;
    private final AccountProperties properties;

    public AccountRedisStore(StringRedisTemplate redis, AccountProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean issueCode(String phone, String code) {
        Long result = redis.execute(ISSUE_CODE, List.of(codeKey(phone), "campus:account:cooldown:" + phone),
                code, Integer.toString(properties.maxCodeAttempts()),
                Long.toString(properties.codeTtl().toMillis()), Long.toString(properties.resendInterval().toMillis()));
        return Long.valueOf(1).equals(result);
    }

    public boolean consumeCode(String phone, String code) {
        return Long.valueOf(1).equals(redis.execute(CONSUME_CODE, List.of(codeKey(phone)), code));
    }

    public String createToken(UserIdentity identity) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(tokenKey(token), identity.userId() + ":" + identity.role().name(), properties.tokenTtl());
        return token;
    }

    public UserIdentity findAndRefresh(String token) {
        // GETEX 原子读取并续期；key 已过期或退出删除后，不会重新创建登录态。
        String value = redis.opsForValue().getAndExpire(tokenKey(token), properties.tokenTtl());
        if (value == null) return null;
        String[] parts = value.split(":", 2);
        return new UserIdentity(Long.parseLong(parts[0]), Role.valueOf(parts[1]));
    }

    public void deleteToken(String token) { redis.delete(tokenKey(token)); }

    public static String tokenKey(String token) { return "campus:account:token:" + token; }
    public static String codeKey(String phone) { return "campus:account:code:" + phone; }

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}

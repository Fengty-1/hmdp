package com.campusbooking.registration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@Component
public class RequestIdGenerator {
    private static final long EPOCH = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();
    private final StringRedisTemplate redis;
    private final Clock clock;

    public RequestIdGenerator(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public String next() {
        Instant now = clock.instant();
        long seconds = now.getEpochSecond() - EPOCH;
        if (seconds < 0 || seconds > Integer.MAX_VALUE) throw new IllegalStateException("ID clock outside range");
        // 日序列不设 TTL；时钟回拨时仍沿用原日期的序列，禁止清除/回退生产计数器。
        Long sequence = redis.opsForValue().increment("registration:id:" + now.atOffset(ZoneOffset.UTC).toLocalDate());
        if (sequence == null || sequence <= 0 || sequence > 0xffffffffL)
            throw new IllegalStateException("ID sequence exhausted");
        return Long.toString((seconds << 32) | sequence);
    }
}

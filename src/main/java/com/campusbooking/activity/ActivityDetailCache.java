package com.campusbooking.activity;

import com.campusbooking.activity.dto.ActivityViews.Detail;
import com.campusbooking.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class ActivityDetailCache {
    private static final Logger log = LoggerFactory.getLogger(ActivityDetailCache.class);
    private static final String NULL_VALUE = "null";
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ObjectMapper json;
    private final ActivityProperties properties;

    public ActivityDetailCache(StringRedisTemplate redis, RedissonClient redisson,
                               ObjectMapper json, ActivityProperties properties) {
        this.redis = redis;
        this.redisson = redisson;
        this.json = json;
        this.properties = properties;
    }

    public static String key(long id) { return "campus:activity:detail:" + id; }
    public static String lockKey(long id) { return "campus:activity:detail-lock:" + id; }

    public Detail get(long id, Supplier<Detail> loader) {
        String cached = redis.opsForValue().get(key(id));
        if (cached != null) {
            log.debug("Activity detail cache hit activityId={} empty={}", id, NULL_VALUE.equals(cached));
            return decode(cached);
        }
        return withLock(id, () -> {
            // 等锁期间其他请求可能已经重建，因此必须再次检查缓存。
            String second = redis.opsForValue().get(key(id));
            if (second != null) return decode(second);

            Detail detail = loader.get();
            Duration ttl = detail == null ? properties.nullTtl()
                    : properties.detailTtl().plusMillis(ThreadLocalRandom.current()
                    .nextLong(properties.ttlJitter().toMillis() + 1));
            try {
                redis.opsForValue().set(key(id), detail == null ? NULL_VALUE : json.writeValueAsString(detail), ttl);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Activity detail serialization failed", exception);
            }
            log.debug("Activity detail loaded from MySQL activityId={} empty={}", id, detail == null);
            return detail;
        });
    }

    public void invalidate(long id) { redis.delete(key(id)); }

    // 写操作持同一把锁直到数据库提交、缓存删除，防止旧回源结果在删除后写回。
    public <T> T withLock(long id, Supplier<T> operation) {
        var lock = redisson.getLock(lockKey(id));
        boolean acquired = false;
        try {
            // 不指定固定 leaseTime，使用 Redisson watchdog；等待时间有界。
            acquired = lock.tryLock(properties.lockWait().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw busy();
            return operation.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw busy();
        } catch (RedisException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CACHE_UNAVAILABLE", "活动缓存暂不可用");
        } finally {
            if (acquired) {
                try {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                } catch (RedisException exception) {
                    // 释放失败不能掩盖“数据库已提交”的返回语义；锁在续期停止后过期。
                    log.warn("Activity lock release failed activityId={} reason={}",
                            id, exception.getClass().getSimpleName());
                }
            }
        }
    }

    private Detail decode(String value) {
        if (NULL_VALUE.equals(value)) return null;
        try { return json.readValue(value, Detail.class); }
        catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CACHE_INVALID", "活动缓存格式异常");
        }
    }

    private ApiException busy() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ACTIVITY_BUSY", "活动正在更新，请稍后查询");
    }
}

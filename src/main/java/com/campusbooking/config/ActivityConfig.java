package com.campusbooking.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration
public class ActivityConfig {
    @Bean
    Clock activityClock() { return Clock.systemUTC(); }

    // 仅用 Redisson 的锁；Account 与业务数据仍由 Lettuce/StringRedisTemplate 访问。
    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        config.setThreads(2).setNettyThreads(2);
        var server = config.useSingleServer()
                .setAddress((properties.getSsl().isEnabled() ? "rediss://" : "redis://")
                        + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase())
                .setConnectTimeout((int) properties.getConnectTimeout().toMillis())
                .setTimeout((int) properties.getTimeout().toMillis())
                .setRetryAttempts(1)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(4);
        if (properties.getPassword() != null && !properties.getPassword().isBlank())
            server.setPassword(properties.getPassword());
        if (properties.getUsername() != null && !properties.getUsername().isBlank())
            server.setUsername(properties.getUsername());
        return Redisson.create(config);
    }
}

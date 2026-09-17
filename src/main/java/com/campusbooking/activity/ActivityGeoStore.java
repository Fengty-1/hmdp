package com.campusbooking.activity;

import com.campusbooking.activity.model.Location;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ActivityGeoStore {
    public static final String KEY = "campus:activity:locations:geo";
    private final StringRedisTemplate redis;

    public ActivityGeoStore(StringRedisTemplate redis) { this.redis = redis; }

    public void index(Location location) {
        redis.opsForGeo().add(KEY, new Point(location.getLongitude(), location.getLatitude()),
                location.getId().toString());
    }

    public Map<Long, Double> nearby(double longitude, double latitude, double radiusKm) {
        var results = redis.opsForGeo().radius(KEY,
                new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending());
        Map<Long, Double> distances = new LinkedHashMap<>();
        if (results != null) results.forEach(result -> distances.put(
                Long.valueOf(result.getContent().getName()), result.getDistance().getValue()));
        return distances;
    }
}

package com.campusbooking.activity.dto;

import com.campusbooking.activity.model.Location;
import java.time.Instant;
import java.util.List;

public final class ActivityViews {
    private ActivityViews() {}

    public record SessionView(Long id, int capacity, Instant registrationStartAt,
                              Instant registrationEndAt, Instant startAt, Instant endAt, String status) {}
    public record Detail(Long id, Long organizerId, String title, String description,
                         String coverImage, Location location, List<SessionView> sessions) {}
    public record Summary(Long id, String title, String coverImage, Long locationId,
                          String locationName, Double distanceKm) {}
    public record Page<T>(int page, int size, long total, List<T> items) {}
    public record PublishResult(Long sessionId, String status, boolean runtimeReady,
                                boolean geoReady, boolean displayReady, String message) {}
}

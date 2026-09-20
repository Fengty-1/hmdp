package com.campusbooking.registration;

import java.time.Instant;

public final class RegistrationViews {
    private RegistrationViews() {}
    public record Accepted(String requestId, String status, String message) {}
    public record Result(String requestId, long userId, long sessionId, String status,
                         Long registrationId, String failureCode, Instant completedAt) {}
    public record Registration(long id, String requestId, long userId, long sessionId, Instant createdAt) {}
}

package com.campusbooking.participation;

import java.time.Instant;

public final class ParticipationViews {
    private ParticipationViews() {}
    public record Credential(long registrationId, String code) {}
    public record Attendance(long registrationId, long userId, String nickname, long sessionId,
                             String activityTitle, long checkedBy, Instant checkedAt) {}
    public record Eligible(long registrationId, long userId, long sessionId, long organizerId,
                           Instant startAt, Instant endAt) {}
}

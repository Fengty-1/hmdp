package com.campusbooking.registration;

public record RegistrationMessage(String requestId, long userId, long sessionId, long acceptedAt) {
    public void validate() {
        if (requestId == null || !requestId.matches("[1-9][0-9]{0,18}")
                || userId <= 0 || sessionId <= 0 || acceptedAt <= 0)
            throw new IllegalArgumentException("Invalid registration message");
        Long.parseLong(requestId);
    }
}

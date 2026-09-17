package com.campusbooking.activity.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class ActivityRequests {
    private ActivityRequests() {}

    public record LocationCreate(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 255) String address,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-85.05112878") @DecimalMax("85.05112878") Double latitude) {}

    public record ActivityCreate(
            @NotNull @Positive Long locationId,
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 5000) String description,
            @Size(max = 500) String coverImage) {}

    public record DisplayUpdate(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 5000) String description,
            @Size(max = 500) String coverImage) {}

    // 必须传带时区的 ISO-8601 时间；数据库和 Redis 均使用 UTC 毫秒精度。
    public record SessionWrite(
            @NotNull @Min(1) @Max(100000) Integer capacity,
            @NotNull Instant registrationStartAt,
            @NotNull Instant registrationEndAt,
            @NotNull Instant startAt,
            @NotNull Instant endAt) {}
}

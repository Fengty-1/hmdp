package com.campusbooking.activity;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.activity")
public record ActivityProperties(
        @NotNull @DurationMin(seconds = 1) Duration detailTtl,
        @NotNull @DurationMin(seconds = 1) Duration nullTtl,
        @NotNull @DurationMin(seconds = 1) Duration ttlJitter,
        @NotNull @DurationMin(millis = 1) Duration lockWait,
        @NotNull @DurationMin(hours = 1) Duration runtimeBuffer) {}

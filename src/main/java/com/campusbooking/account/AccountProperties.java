package com.campusbooking.account;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.account")
public record AccountProperties(
        @NotNull @DurationMin(seconds = 1) Duration codeTtl,
        @NotNull @DurationMin(seconds = 1) Duration resendInterval,
        @Min(1) @Max(10) int maxCodeAttempts,
        @NotNull @DurationMin(seconds = 1) Duration tokenTtl,
        boolean devSmsEnabled) {
}

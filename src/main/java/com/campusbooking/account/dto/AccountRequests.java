package com.campusbooking.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AccountRequests {
    private AccountRequests() {}

    public record CodeRequest(@NotBlank @Pattern(regexp = "1[3-9]\\d{9}") String phone) {}

    public record LoginRequest(
            @NotBlank @Pattern(regexp = "1[3-9]\\d{9}") String phone,
            @NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    public record ProfileUpdate(@NotBlank @Size(max = 32) String nickname) {}
}
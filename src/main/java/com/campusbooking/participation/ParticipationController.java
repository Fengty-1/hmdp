package com.campusbooking.participation;

import com.campusbooking.activity.dto.ActivityViews.Page;
import com.campusbooking.common.ApiResponse;
import com.campusbooking.participation.ParticipationViews.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ParticipationController {
    private final ParticipationService service;
    public ParticipationController(ParticipationService service) { this.service = service; }

    public record VerifyRequest(@NotBlank @Pattern(regexp = "[0-9a-f]{32}") String code) {}

    @PostMapping("/registrations/{registrationId}/credential")
    public ApiResponse<Credential> issue(@PathVariable long registrationId) {
        return ApiResponse.ok(service.issue(registrationId));
    }

    @PostMapping("/sessions/{sessionId}/participations/verify")
    public ApiResponse<Attendance> verify(@PathVariable long sessionId, @Valid @RequestBody VerifyRequest request) {
        return ApiResponse.ok(service.verify(sessionId, request.code()));
    }

    @GetMapping("/participations/me")
    public ApiResponse<Page<Attendance>> mine(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.mine(page, size));
    }

    @GetMapping("/sessions/{sessionId}/participations")
    public ApiResponse<Page<Attendance>> attendees(@PathVariable long sessionId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.attendees(sessionId, page, size));
    }
}

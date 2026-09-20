package com.campusbooking.registration;

import com.campusbooking.activity.dto.ActivityViews.Page;
import com.campusbooking.common.ApiResponse;
import com.campusbooking.registration.RegistrationViews.*;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RegistrationController {
    private final RegistrationService service;
    public RegistrationController(RegistrationService service) { this.service = service; }

    @PostMapping("/sessions/{sessionId}/registrations")
    public ResponseEntity<ApiResponse<Accepted>> submit(@PathVariable @Positive long sessionId) {
        return ResponseEntity.accepted().body(ApiResponse.ok(service.submit(sessionId)));
    }

    @GetMapping("/registrations/results/{requestId}")
    public ApiResponse<Result> result(@PathVariable String requestId) {
        return ApiResponse.ok(service.result(requestId));
    }

    @GetMapping("/registrations/me")
    public ApiResponse<Page<Registration>> mine(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.mine(page, size));
    }
}

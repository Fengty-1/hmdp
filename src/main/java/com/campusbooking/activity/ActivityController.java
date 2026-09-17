package com.campusbooking.activity;

import com.campusbooking.activity.dto.ActivityRequests.*;
import com.campusbooking.activity.dto.ActivityViews.*;
import com.campusbooking.activity.model.Activity;
import com.campusbooking.activity.model.Location;
import com.campusbooking.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ActivityController {
    private final ActivityService service;

    public ActivityController(ActivityService service) { this.service = service; }

    @PostMapping("/organizer/locations")
    public ApiResponse<Location> createLocation(@Valid @RequestBody LocationCreate request) {
        return ApiResponse.ok(service.createLocation(request));
    }

    @GetMapping("/locations")
    public ApiResponse<Page<Location>> locations(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.locations(page, size));
    }

    @PostMapping("/organizer/activities")
    public ApiResponse<Activity> create(@Valid @RequestBody ActivityCreate request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/organizer/activities/{id}")
    public ApiResponse<Detail> ownerDetail(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.ownerDetail(id));
    }

    @PatchMapping("/organizer/activities/{id}")
    public ApiResponse<Void> update(@PathVariable @Positive long id, @Valid @RequestBody DisplayUpdate request) {
        service.updateDisplay(id, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/organizer/activities/{id}/sessions")
    public ApiResponse<SessionView> createSession(@PathVariable @Positive long id,
                                                 @Valid @RequestBody SessionWrite request) {
        return ApiResponse.ok(service.createSession(id, request));
    }

    @PutMapping("/organizer/activities/{id}/sessions/{sessionId}")
    public ApiResponse<SessionView> editSession(@PathVariable @Positive long id,
            @PathVariable @Positive long sessionId, @Valid @RequestBody SessionWrite request) {
        return ApiResponse.ok(service.editSession(id, sessionId, request));
    }

    @PostMapping("/organizer/activities/{id}/sessions/{sessionId}/publish")
    public ResponseEntity<ApiResponse<PublishResult>> publish(@PathVariable @Positive long id,
                                                             @PathVariable @Positive long sessionId) {
        PublishResult result = service.publish(id, sessionId);
        boolean ready = result.runtimeReady() && result.geoReady() && result.displayReady();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(new ApiResponse<>(ready ? "OK" : "PUBLISH_SYNC_INCOMPLETE", result.message(), result));
    }

    @GetMapping("/activities")
    public ApiResponse<Page<Summary>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.list(page, size));
    }

    @GetMapping("/activities/{id}")
    public ApiResponse<Detail> detail(@PathVariable @Positive long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @GetMapping("/activities/nearby")
    public ApiResponse<Page<Summary>> nearby(@RequestParam double longitude, @RequestParam double latitude,
            @RequestParam(defaultValue = "5") double radiusKm, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.nearby(longitude, latitude, radiusKm, page, size));
    }
}

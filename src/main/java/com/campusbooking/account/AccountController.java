package com.campusbooking.account;

import com.campusbooking.account.auth.RefreshTokenInterceptor;
import com.campusbooking.account.auth.UserContext;
import com.campusbooking.account.dto.AccountRequests.CodeRequest;
import com.campusbooking.account.dto.AccountRequests.LoginRequest;
import com.campusbooking.account.dto.AccountRequests.ProfileUpdate;
import com.campusbooking.account.dto.AccountViews.CodeReceipt;
import com.campusbooking.account.dto.AccountViews.LoginResult;
import com.campusbooking.account.dto.AccountViews.Profile;
import com.campusbooking.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @PostMapping("/code")
    public ApiResponse<CodeReceipt> sendCode(@Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(service.sendCode(request.phone()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(service.login(request.phone(), request.code()));
    }

    @GetMapping("/me")
    public ApiResponse<Profile> me() {
        return ApiResponse.ok(service.currentUser(UserContext.require().userId()));
    }

    @PatchMapping("/me")
    public ApiResponse<Profile> updateProfile(@Valid @RequestBody ProfileUpdate request) {
        return ApiResponse.ok(service.updateProfile(UserContext.require().userId(), request.nickname()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        service.logout(UserContext.require().userId(),
                (String) request.getAttribute(RefreshTokenInterceptor.TOKEN_ATTRIBUTE));
        return ApiResponse.ok(null);
    }
}

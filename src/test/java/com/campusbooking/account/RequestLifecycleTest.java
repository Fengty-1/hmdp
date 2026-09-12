package com.campusbooking.account;

import com.campusbooking.account.auth.AccountRedisStore;
import com.campusbooking.account.auth.LoginInterceptor;
import com.campusbooking.account.auth.RefreshTokenInterceptor;
import com.campusbooking.account.auth.UserContext;
import com.campusbooking.account.auth.UserIdentity;
import com.campusbooking.account.dto.AccountViews.Profile;
import com.campusbooking.account.model.Role;
import com.campusbooking.common.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestLifecycleTest {
    static final String TOKEN = "a".repeat(32);
    AccountRedisStore store;
    AccountService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(AccountRedisStore.class);
        service = mock(AccountService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AccountController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .addInterceptors(new RefreshTokenInterceptor(store), new LoginInterceptor()).build();
    }

    @AfterEach
    void assertContextWasCleared() {
        try { assertThat(UserContext.current()).isNull(); }
        finally { UserContext.clear(); }
    }

    @Test
    void authenticatedRequestRestoresIdentityAndNextAnonymousRequestIsRejected() throws Exception {
        authenticate();
        when(service.currentUser(7L)).thenAnswer(invocation -> {
            assertThat(UserContext.require().userId()).isEqualTo(7L);
            return new Profile(7L, "13800000001", "同学", Role.STUDENT);
        });
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(7))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(UserContext.current()).isNull();
        mvc.perform(get("/api/account/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void serviceFailureClearsIdentity() throws Exception {
        authenticate();
        when(service.currentUser(7L)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void invalidRequestBodyClearsIdentity() throws Exception {
        authenticate();
        mvc.perform(patch("/api/account/me").header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void redisFailureCannotFallBackToAuthenticatedAccess() throws Exception {
        when(store.findAndRefresh(TOKEN)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        mvc.perform(get("/api/account/me").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(service);
    }

    @Test
    void malformedAuthorizationIsRejectedBeforeRedisAccess() throws Exception {
        mvc.perform(get("/api/account/me").header("Authorization", "bad token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(store, service);
    }

    private void authenticate() {
        when(store.findAndRefresh(TOKEN)).thenReturn(new UserIdentity(7L, Role.STUDENT));
    }
}

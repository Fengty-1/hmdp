package com.campusbooking.account.auth;

import com.campusbooking.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

@Component
public class RefreshTokenInterceptor implements AsyncHandlerInterceptor {
    public static final String TOKEN_ATTRIBUTE = RefreshTokenInterceptor.class.getName() + ".token";
    private final AccountRedisStore store;

    public RefreshTokenInterceptor(AccountRedisStore store) { this.store = store; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.clear();
        response.setHeader("Cache-Control", "no-store");
        String header = request.getHeader("Authorization");
        if (header == null) return true;
        if (!header.matches("(?i)Bearer [a-f0-9]{32}")) throw ApiException.unauthorized();
        String token = header.substring(7);
        UserIdentity user = store.findAndRefresh(token);
        if (user == null) throw ApiException.unauthorized();
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        UserContext.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.clear();
    }
}

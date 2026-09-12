package com.campusbooking.config;

import com.campusbooking.account.auth.LoginInterceptor;
import com.campusbooking.account.auth.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final RefreshTokenInterceptor refreshToken;
    private final LoginInterceptor login;

    public WebConfig(RefreshTokenInterceptor refreshToken, LoginInterceptor login) {
        this.refreshToken = refreshToken;
        this.login = login;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshToken).addPathPatterns("/api/**").order(0);
        registry.addInterceptor(login).addPathPatterns("/api/**")
                .excludePathPatterns("/api/account/code", "/api/account/login").order(1);
    }
}

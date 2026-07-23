package com.rxscan.backend.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** JWT gate on the consumer-plane routes. /v1/auth/** and /extract stay open. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtService jwt;

    public WebConfig(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtInterceptor(jwt))
                .addPathPatterns("/v1/me/**", "/v1/prescriptions/**");
    }
}

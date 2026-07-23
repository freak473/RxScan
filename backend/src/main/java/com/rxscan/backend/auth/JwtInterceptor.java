package com.rxscan.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Plain interceptor auth — no spring-security. Contract rule: any 401 makes the
 * FE clear its token and route to signin (no refresh tokens in slice A).
 */
public class JwtInterceptor implements HandlerInterceptor {

    public static final String ATTR_PUBLIC_ID = "rxscan.publicId";

    private final JwtService jwt;

    public JwtInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<String> sub = jwt.verify(header.substring(7));
            if (sub.isPresent()) {
                req.setAttribute(ATTR_PUBLIC_ID, sub.get());
                return true;
            }
        }
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":{\"code\":\"unauthorized\",\"message\":\"Missing or invalid token\"}}");
        return false;
    }
}

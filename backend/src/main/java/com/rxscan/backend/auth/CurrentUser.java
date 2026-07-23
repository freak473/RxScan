package com.rxscan.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves the JWT sub (public_id) set by JwtInterceptor to the internal user row. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public UserRepository.UserRow require(HttpServletRequest req) {
        Object publicId = req.getAttribute(JwtInterceptor.ATTR_PUBLIC_ID);
        if (publicId == null) throw new ApiException(401, "unauthorized", "Missing token");
        return users.findByPublicId(UUID.fromString((String) publicId))
                .orElseThrow(() -> new ApiException(401, "unauthorized", "Unknown user"));
    }
}

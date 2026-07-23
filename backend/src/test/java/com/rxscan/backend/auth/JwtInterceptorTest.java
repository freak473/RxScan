package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class JwtInterceptorTest {

    JwtService jwt = new JwtService("test-jwt-secret", 30, Clock.systemUTC());
    JwtInterceptor interceptor = new JwtInterceptor(jwt);

    @Test
    void validBearerTokenPassesAndSetsPublicId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt.mint("some-public-id"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, res, new Object())).isTrue();
        assertThat(req.getAttribute(JwtInterceptor.ATTR_PUBLIC_ID)).isEqualTo("some-public-id");
    }

    @Test
    void missingOrBadTokenIs401WithErrorShape() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"unauthorized\"");

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Authorization", "Bearer garbage");
        assertThat(interceptor.preHandle(bad, new MockHttpServletResponse(), new Object())).isFalse();
    }
}

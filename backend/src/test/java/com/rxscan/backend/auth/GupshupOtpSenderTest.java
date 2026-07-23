package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request-shape test ONLY — never calls Gupshup for real (CLAUDE.md: no live SMS). */
class GupshupOtpSenderTest {

    @Test
    void sendsDltCompliantFormPost() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GupshupOtpSender sender = new GupshupOtpSender(
                builder.baseUrl("https://enterprise.smsgupshup.com").build(),
                "uid", "pw", "entity-1", "template-1", "Your RxScan OTP is %s");

        LinkedMultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("method", "SendMessage");
        expected.add("send_to", "+919876543210");
        expected.add("msg", "Your RxScan OTP is 123456");
        expected.add("msg_type", "TEXT");
        expected.add("userid", "uid");
        expected.add("password", "pw");
        expected.add("v", "1.1");
        expected.add("auth_scheme", "plain");
        expected.add("format", "json");
        expected.add("principalEntityId", "entity-1");
        expected.add("dltTemplateId", "template-1");

        server.expect(requestTo("https://enterprise.smsgupshup.com/GatewayAPI/rest"))
                .andExpect(method(POST))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("{\"response\":{\"status\":\"success\"}}", APPLICATION_JSON));

        sender.send("+919876543210", "123456");
        server.verify();
    }

    @Test
    void gupshupErrorStatusThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GupshupOtpSender sender = new GupshupOtpSender(
                builder.baseUrl("https://enterprise.smsgupshup.com").build(),
                "uid", "pw", "e", "t", "OTP %s");
        server.expect(requestTo("https://enterprise.smsgupshup.com/GatewayAPI/rest"))
                .andRespond(withSuccess("{\"response\":{\"status\":\"error\",\"details\":\"bad creds\"}}", APPLICATION_JSON));

        assertThatThrownBy(() -> sender.send("+919876543210", "123456"))
                .isInstanceOf(OtpDeliveryException.class);
    }
}

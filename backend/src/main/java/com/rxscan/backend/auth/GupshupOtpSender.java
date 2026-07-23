package com.rxscan.backend.auth;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Gupshup Enterprise SMS (SMS only — no WhatsApp OTP, product decision). Dormant
 * until the contract + DLT sender-ID/template registration close (see CHECKLIST).
 * DLT params (principalEntityId, dltTemplateId) are mandatory for Indian A2P SMS.
 */
public class GupshupOtpSender implements OtpSender {

    private final RestClient client;
    private final String userId;
    private final String password;
    private final String principalEntityId;
    private final String templateId;
    private final String template;   // e.g. "Your RxScan OTP is %s" — must match the DLT-registered text

    public GupshupOtpSender(RestClient client, String userId, String password,
                            String principalEntityId, String templateId, String template) {
        this.client = client;
        this.userId = userId;
        this.password = password;
        this.principalEntityId = principalEntityId;
        this.templateId = templateId;
        this.template = template;
    }

    @Override
    public void send(String phone, String otp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("method", "SendMessage");
        form.add("send_to", phone);
        form.add("msg", template.formatted(otp));
        form.add("msg_type", "TEXT");
        form.add("userid", userId);
        form.add("password", password);
        form.add("v", "1.1");
        form.add("auth_scheme", "plain");
        form.add("format", "json");
        form.add("principalEntityId", principalEntityId);
        form.add("dltTemplateId", templateId);
        try {
            String body = client.post().uri("/GatewayAPI/rest")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            // ponytail: string sniff instead of a response DTO — Gupshup's envelope is
            // {"response":{"status":"success"|"error",...}}; upgrade to a typed parse if it grows.
            if (body == null || body.contains("\"status\":\"error\"")) {
                throw new OtpDeliveryException("Gupshup rejected the send: " + body, null);
            }
        } catch (OtpDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OtpDeliveryException("Gupshup call failed", e);
        }
    }
}

package com.rxscan.backend.auth;

/** OTP delivery strategy (mirrors the vision-provider pattern). */
public interface OtpSender {
    void send(String phone, String otp);
}

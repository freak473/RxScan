package com.rxscan.backend.auth;

/** Sends nothing; the accepted code is rxscan.auth.dev-otp. Default until the Gupshup contract closes. */
public class StubOtpSender implements OtpSender {
    @Override
    public void send(String phone, String otp) {
        // intentionally nothing
    }
}

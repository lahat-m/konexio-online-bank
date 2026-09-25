package com.konexio.bank.identity.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Captures the codes the identity module "sends", so an integration test can
 * walk the real sign-up flow instead of reaching into the database — where the
 * code exists only as an HMAC and could not be read back anyway.
 *
 * <p>Lives in the domain package because {@link OtpSender} is package-private:
 * the port is internal to the module, and a test double for it belongs beside it.
 */
public class RecordingOtpSender implements OtpSender {

    private final List<String> codes = new ArrayList<>();

    @Override
    public synchronized void send(String phone, String code, Duration validFor) {
        codes.add(code);
    }

    public synchronized String lastCode() {
        if (codes.isEmpty()) {
            throw new IllegalStateException("No OTP has been sent");
        }
        return codes.getLast();
    }

    public synchronized int sentCount() {
        return codes.size();
    }

    public synchronized void clear() {
        codes.clear();
    }

    /** Imported by the integration tests; {@code app.identity.otp.sender=test} keeps the logging sender out. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        RecordingOtpSender recordingOtpSender() {
            return new RecordingOtpSender();
        }
    }
}

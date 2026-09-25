package com.konexio.bank.identity.domain;

import com.konexio.bank.identity.config.IdentityProperties;
import com.konexio.bank.shared.util.Masks;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Development stand-in for the SMS gateway.
 *
 * <p>Prints the code itself only in development, because a sign-up cannot be
 * finished without it and no gateway is integrated yet. Two things turn that on,
 * and both mean "this is somebody's laptop":
 *
 * <ul>
 *   <li>{@code app.identity.otp.log-codes=true}, set deliberately — the explicit
 *       switch, for a local run of a packaged jar or anywhere else devtools is
 *       not involved.
 *   <li>Spring Boot Devtools on the classpath. It is an optional dependency that
 *       the Maven plugin leaves out of the repackaged jar and that disables
 *       itself when started from one, so its presence is as close to "running
 *       from an IDE" as this application can observe.
 * </ul>
 *
 * <p>Neither can happen in production, which matters more here than convenience:
 * an OTP in a log file is an OTP in every system that ships logs, and a code
 * that authorises opening a bank account is worth as much as the PIN it leads
 * to. When it does print, it prints at WARN saying so.
 */
@Component
@ConditionalOnProperty(prefix = "app.identity.otp", name = "sender", havingValue = "log", matchIfMissing = true)
class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    private static final boolean DEVTOOLS_PRESENT = ClassUtils.isPresent(
            "org.springframework.boot.devtools.restart.Restarter", LoggingOtpSender.class.getClassLoader());

    private final boolean logCodes;

    LoggingOtpSender(IdentityProperties properties) {
        this.logCodes = properties.otp().logCodes() || DEVTOOLS_PRESENT;
    }

    @Override
    public void send(String phone, String code, Duration validFor) {
        if (logCodes) {
            log.warn("OTP for {} is {} (valid {}). Development only — no SMS gateway is integrated, "
                    + "and a real deployment neither runs devtools nor sets app.identity.otp.log-codes.",
                    Masks.phone(phone), code, validFor);
        } else {
            log.info("OTP sent to {} (valid {})", Masks.phone(phone), validFor);
        }
    }
}

package com.konexio.bank.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A one-time code sent by SMS. Only the HMAC of the code is stored, so the table
 * alone cannot be read to log anyone in.
 *
 * <p>A resend updates the open challenge in place — new code, {@code sent_count + 1} —
 * rather than inserting a second row, because a partial unique index allows only
 * one unverified challenge per registration. The attempt counter resets with the
 * new code: the previous code no longer works, so its failures should not spend
 * the budget for this one. Abuse is bounded by {@code sent_count}, which the
 * schema caps at 5.
 */
@Entity
@Table(schema = "identity", name = "otp_challenge")
class OtpChallenge {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false)
    private OtpPurpose purpose;

    @Column(name = "registration_id")
    private UUID registrationId;

    @Column(name = "credential_id")
    private UUID credentialId;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "code_hash", nullable = false)
    private byte[] codeHash;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;

    @Column(name = "sent_count", nullable = false)
    private short sentCount;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OtpChallenge() {}

    static OtpChallenge forRegistration(
            UUID registrationId, String phone, byte[] codeHash, int maxAttempts, Duration ttl) {
        OtpChallenge challenge = new OtpChallenge();
        challenge.purpose = OtpPurpose.REGISTRATION;
        challenge.registrationId = registrationId;
        challenge.phone = phone;
        challenge.codeHash = codeHash;
        challenge.attempts = 0;
        challenge.maxAttempts = (short) maxAttempts;
        challenge.sentCount = 1;
        challenge.createdAt = Instant.now();
        challenge.lastSentAt = challenge.createdAt;
        challenge.expiresAt = challenge.createdAt.plus(ttl);
        return challenge;
    }

    void resend(byte[] newCodeHash, Duration ttl, Instant now) {
        this.codeHash = newCodeHash;
        this.sentCount = (short) (sentCount + 1);
        this.attempts = 0;
        this.lastSentAt = now;
        this.expiresAt = now.plus(ttl);
    }

    void recordFailedAttempt() {
        this.attempts = (short) (attempts + 1);
    }

    void markVerified(Instant now) {
        this.verifiedAt = now;
    }

    boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    boolean attemptsExhausted() {
        return attempts >= maxAttempts;
    }

    int attemptsUsed() {
        return attempts;
    }

    boolean sendLimitReached(int maxSends) {
        return sentCount >= maxSends;
    }

    boolean matches(byte[] candidateHash) {
        return java.security.MessageDigest.isEqual(codeHash, candidateHash);
    }

    Instant getLastSentAt() {
        return lastSentAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    String getPhone() {
        return phone;
    }
}

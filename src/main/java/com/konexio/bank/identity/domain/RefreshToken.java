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
 * An opaque, rotating refresh token — only its SHA-256 is stored.
 *
 * <p>Every use mints a successor in the same {@code familyId} and marks this row
 * rotated. A token that is presented after it was rotated (or revoked) can only
 * mean a copy escaped, so the whole family is revoked rather than just that one
 * row: the legitimate device is forced to log in again, and the thief's copy is
 * worthless. That is the whole reason {@code familyId} exists.
 */
@Entity
@Table(schema = "identity", name = "refresh_token")
class RefreshToken {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "device_id", updatable = false)
    private String deviceId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private RevokeReason revokeReason;

    protected RefreshToken() {}

    static RefreshToken issue(UUID familyId, UUID customerId, String deviceId, byte[] tokenHash, Duration ttl) {
        RefreshToken token = new RefreshToken();
        token.familyId = familyId;
        token.subjectType = SubjectType.CUSTOMER;
        token.subjectId = customerId;
        token.deviceId = deviceId;
        token.tokenHash = tokenHash;
        token.issuedAt = Instant.now();
        token.expiresAt = token.issuedAt.plus(ttl);
        return token;
    }

    /** Both fields move together — a DB CHECK enforces that they are set or null as a pair. */
    void rotateTo(RefreshToken replacement, Instant now) {
        this.rotatedAt = now;
        this.replacedBy = replacement.id;
    }

    void revoke(RevokeReason reason, Instant now) {
        this.revokedAt = now;
        this.revokeReason = reason;
    }

    boolean isSpent() {
        return rotatedAt != null || revokedAt != null;
    }

    boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    UUID getId() {
        return id;
    }

    UUID getFamilyId() {
        return familyId;
    }

    UUID getSubjectId() {
        return subjectId;
    }

    String getDeviceId() {
        return deviceId;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}

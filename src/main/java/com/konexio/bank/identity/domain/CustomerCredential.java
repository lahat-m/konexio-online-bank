package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.domain.BaseEntity;
import com.konexio.bank.shared.util.Uuid7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A customer's login identity: the PIN they authenticate with and the lockout
 * state that protects it.
 *
 * <p>{@code customerId} — not {@code id} — is the stable identifier the rest of
 * the bank knows: it is the JWT {@code sub} and the primary key of
 * {@code customer.customer}. It is generated here rather than by the column
 * default so the value is known before the insert and can go straight into the
 * registration response and the {@code CustomerRegistered} event.
 *
 * <p>The PIN is only ever an Argon2id hash, and the National ID only ever an
 * HMAC under a server-side pepper plus its last four digits (enough to show on
 * a "is this you?" screen).
 */
@Entity
@Table(schema = "identity", name = "customer_credential")
class CustomerCredential extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "national_id_hash", nullable = false)
    private byte[] nationalIdHash;

    @Column(name = "national_id_last4", nullable = false, length = 4)
    private String nationalIdLast4;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "email")
    private String email;

    @Column(name = "pin_hash", nullable = false)
    private String pinHash;

    @Column(name = "pin_changed_at", nullable = false)
    private Instant pinChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_level", nullable = false)
    private KycLevel kycLevel;

    @Column(name = "kyc_reference")
    private String kycReference;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CredentialStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private short failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected CustomerCredential() {}

    CustomerCredential(
            String phone,
            String fullName,
            byte[] nationalIdHash,
            String nationalIdLast4,
            LocalDate dateOfBirth,
            String email,
            String pinHash,
            String kycReference) {
        this.customerId = Uuid7.generate();
        this.phone = phone;
        this.fullName = fullName;
        this.nationalIdHash = nationalIdHash;
        this.nationalIdLast4 = nationalIdLast4;
        this.dateOfBirth = dateOfBirth;
        this.email = email;
        this.pinHash = pinHash;
        this.pinChangedAt = Instant.now();
        this.kycLevel = KycLevel.VERIFIED;
        this.kycReference = kycReference;
        this.kycVerifiedAt = Instant.now();
        this.status = CredentialStatus.ACTIVE;
        this.failedAttempts = 0;
    }

    boolean isLocked(Instant now) {
        return status == CredentialStatus.LOCKED && lockedUntil != null && lockedUntil.isAfter(now);
    }

    boolean isDisabled() {
        return status == CredentialStatus.DISABLED;
    }

    /**
     * Clears an expired lock. Returns true when it actually unlocked, so the
     * caller can record the {@code CREDENTIAL_UNLOCKED} event once rather than
     * on every subsequent login.
     */
    boolean unlockIfLockExpired(Instant now) {
        if (status != CredentialStatus.LOCKED || (lockedUntil != null && lockedUntil.isAfter(now))) {
            return false;
        }
        this.status = CredentialStatus.ACTIVE;
        this.lockedUntil = null;
        this.failedAttempts = 0;
        return true;
    }

    /** Counts a wrong PIN, locking the credential once the threshold is reached. Returns true if it locked. */
    boolean recordFailedAttempt(int maxAttempts, Duration lockDuration, Instant now) {
        this.failedAttempts = (short) (failedAttempts + 1);
        if (failedAttempts < maxAttempts) {
            return false;
        }
        this.status = CredentialStatus.LOCKED;
        this.lockedUntil = now.plus(lockDuration);
        return true;
    }

    void recordSuccessfulLogin(Instant now) {
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.status = CredentialStatus.ACTIVE;
        this.lastLoginAt = now;
    }

    void changePin(String newPinHash, Instant now) {
        this.pinHash = newPinHash;
        this.pinChangedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    String getPhone() {
        return phone;
    }

    String getFullName() {
        return fullName;
    }

    String getEmail() {
        return email;
    }

    String getPinHash() {
        return pinHash;
    }

    KycLevel getKycLevel() {
        return kycLevel;
    }

    CredentialStatus getStatus() {
        return status;
    }

    Instant getLockedUntil() {
        return lockedUntil;
    }
}

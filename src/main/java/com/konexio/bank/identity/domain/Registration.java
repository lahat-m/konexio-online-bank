package com.konexio.bank.identity.domain;

import com.konexio.bank.shared.domain.BaseEntity;
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
 * One sign-up session. Holds the applicant's details until the OTP is verified
 * and a PIN is set, at which point a {@link CustomerCredential} is created and
 * linked here.
 *
 * <p>A partial unique index allows only one open session per phone number, so a
 * second sign-up attempt for the same phone is rejected by the database even if
 * two requests race past the service-level check.
 */
@Entity
@Table(schema = "identity", name = "registration")
class Registration extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RegistrationStatus status;

    @Column(name = "kyc_reference")
    private String kycReference;

    @Column(name = "kyc_failure_reason")
    private String kycFailureReason;

    @Column(name = "credential_id")
    private UUID credentialId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Registration() {}

    Registration(
            String phone,
            String fullName,
            byte[] nationalIdHash,
            String nationalIdLast4,
            LocalDate dateOfBirth,
            String email,
            Duration sessionTtl) {
        this.phone = phone;
        this.fullName = fullName;
        this.nationalIdHash = nationalIdHash;
        this.nationalIdLast4 = nationalIdLast4;
        this.dateOfBirth = dateOfBirth;
        this.email = email;
        this.status = RegistrationStatus.STARTED;
        this.expiresAt = Instant.now().plus(sessionTtl);
    }

    void markKycPassed(String reference) {
        this.kycReference = reference;
    }

    void markKycFailed(String reason) {
        this.status = RegistrationStatus.KYC_FAILED;
        this.kycFailureReason = reason;
    }

    void markOtpSent() {
        this.status = RegistrationStatus.OTP_SENT;
    }

    void markOtpVerified() {
        this.status = RegistrationStatus.OTP_VERIFIED;
    }

    void markExpired() {
        this.status = RegistrationStatus.EXPIRED;
    }

    /** Links the credential this session produced. The DB CHECK ties all three fields together. */
    void complete(UUID newCredentialId) {
        this.credentialId = newCredentialId;
        this.completedAt = Instant.now();
        this.status = RegistrationStatus.COMPLETED;
    }

    boolean isExpired(Instant now) {
        return status.isOpen() && expiresAt.isBefore(now);
    }

    UUID getId() {
        return id;
    }

    String getPhone() {
        return phone;
    }

    String getFullName() {
        return fullName;
    }

    byte[] getNationalIdHash() {
        return nationalIdHash;
    }

    String getNationalIdLast4() {
        return nationalIdLast4;
    }

    LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    String getEmail() {
        return email;
    }

    RegistrationStatus getStatus() {
        return status;
    }

    String getKycReference() {
        return kycReference;
    }

    String getKycFailureReason() {
        return kycFailureReason;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }
}

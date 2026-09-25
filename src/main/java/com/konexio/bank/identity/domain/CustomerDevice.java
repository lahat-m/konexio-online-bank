package com.konexio.bank.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A device a customer logs in from. Its {@code deviceId} travels in the access
 * token, so revoking a lost phone here invalidates what that phone can do
 * without touching the customer's other sessions.
 */
@Entity
@Table(schema = "identity", name = "customer_device")
class CustomerDevice {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "credential_id", nullable = false, updatable = false)
    private UUID credentialId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private DevicePlatform platform;

    @Column(name = "model")
    private String model;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CustomerDevice() {}

    CustomerDevice(UUID credentialId, String deviceId, DevicePlatform platform, String model) {
        this.credentialId = credentialId;
        this.deviceId = deviceId;
        this.platform = platform;
        this.model = model;
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = this.firstSeenAt;
    }

    void touch(Instant now) {
        this.lastSeenAt = now;
    }

    void revoke(Instant now) {
        this.revokedAt = now;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    String getDeviceId() {
        return deviceId;
    }
}

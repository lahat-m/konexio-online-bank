package com.konexio.bank.customer.domain;

import com.konexio.bank.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A person the bank holds accounts for.
 *
 * <p>The id is <em>assigned</em>, not generated: it is the identity module's
 * {@code customer_id}, and the column is a foreign key to
 * {@code identity.customer_credential.customer_id}. So a profile cannot exist
 * before the credential it belongs to, and "the customer id in the JWT" and
 * "the primary key of this row" are the same value everywhere in the system —
 * no mapping table, no second identifier to keep in step.
 */
@Entity
@Table(schema = "customer", name = "customer")
class Customer extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "phone", nullable = false)
    private String phone;

    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_level", nullable = false)
    private KycLevel kycLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CustomerStatus status;

    protected Customer() {}

    Customer(UUID customerId, String fullName, String phone, String email, KycLevel kycLevel) {
        this.id = customerId;
        this.fullName = fullName;
        this.phone = phone;
        this.email = email;
        this.kycLevel = kycLevel;
        this.status = CustomerStatus.ACTIVE;
    }

    UUID getId() {
        return id;
    }

    String getFullName() {
        return fullName;
    }

    String getPhone() {
        return phone;
    }

    String getEmail() {
        return email;
    }

    KycLevel getKycLevel() {
        return kycLevel;
    }

    CustomerStatus getStatus() {
        return status;
    }
}

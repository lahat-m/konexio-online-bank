package com.konexio.bank.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerCredentialRepository extends JpaRepository<CustomerCredential, UUID> {

    Optional<CustomerCredential> findByPhone(String phone);

    Optional<CustomerCredential> findByCustomerId(UUID customerId);

    boolean existsByPhone(String phone);

    /** The National ID is never stored in the clear, so uniqueness is checked on its HMAC. */
    boolean existsByNationalIdHash(byte[] nationalIdHash);
}

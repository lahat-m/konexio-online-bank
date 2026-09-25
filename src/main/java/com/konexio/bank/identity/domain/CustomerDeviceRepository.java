package com.konexio.bank.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CustomerDeviceRepository extends JpaRepository<CustomerDevice, UUID> {

    Optional<CustomerDevice> findByCredentialIdAndDeviceId(UUID credentialId, String deviceId);
}

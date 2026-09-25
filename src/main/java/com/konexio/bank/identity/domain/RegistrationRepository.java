package com.konexio.bank.identity.domain;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RegistrationRepository extends JpaRepository<Registration, UUID> {

    boolean existsByPhoneAndStatusIn(String phone, Collection<RegistrationStatus> statuses);
}

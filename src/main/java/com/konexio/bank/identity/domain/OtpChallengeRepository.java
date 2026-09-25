package com.konexio.bank.identity.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    /** The single unverified challenge for a sign-up, as enforced by a partial unique index. */
    Optional<OtpChallenge> findByRegistrationIdAndVerifiedAtIsNull(UUID registrationId);
}

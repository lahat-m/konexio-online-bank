package com.konexio.bank.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    /**
     * Revokes every still-live token of one family in a single statement —
     * used on logout and, more importantly, the moment a rotated token is
     * replayed, where speed matters because the attacker is holding a copy.
     *
     * <p>{@code flushAutomatically} is required alongside {@code clearAutomatically}:
     * this bulk update goes straight to the database, and clearing the
     * persistence context afterwards would otherwise discard pending changes on
     * managed entities that were never flushed first.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now, t.revokeReason = :reason
             where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(
            @Param("familyId") UUID familyId,
            @Param("reason") RevokeReason reason,
            @Param("now") Instant now);
}

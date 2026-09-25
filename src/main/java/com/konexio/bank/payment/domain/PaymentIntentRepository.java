package com.konexio.bank.payment.domain;

import com.konexio.bank.payment.IntentType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    /**
     * Scoped by type as well as id, so {@code GET /api/deposits/{id}} cannot be
     * handed a transfer id and answer with a transfer.
     */
    Optional<PaymentIntent> findByIdAndCustomerIdAndIntentType(UUID id, UUID customerId, IntentType intentType);

    Optional<PaymentIntent> findByChannelAndExternalReference(
            com.konexio.bank.payment.PaymentChannel channel, String externalReference);

    Page<PaymentIntent> findByCustomerIdAndIntentType(UUID customerId, IntentType intentType, Pageable pageable);
}

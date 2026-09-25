package com.konexio.bank.loan.domain;

import com.konexio.bank.loan.OfferStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoanOfferRepository extends JpaRepository<LoanOffer, UUID> {

    List<LoanOffer> findByCustomerIdAndStatus(UUID customerId, OfferStatus status);

    Optional<LoanOffer> findByIdAndCustomerId(UUID id, UUID customerId);
}

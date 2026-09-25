package com.konexio.bank.loan.domain;

import com.konexio.bank.loan.LoanStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoanRepository extends JpaRepository<Loan, UUID> {

    Optional<Loan> findByIdAndCustomerId(UUID id, UUID customerId);

    Page<Loan> findByCustomerId(UUID customerId, Pageable pageable);

    Page<Loan> findByCustomerIdAndStatus(UUID customerId, LoanStatus status, Pageable pageable);

    boolean existsByCustomerIdAndStatusIn(UUID customerId, List<LoanStatus> statuses);

    boolean existsByLinkedAccountIdAndStatusIn(UUID linkedAccountId, List<LoanStatus> statuses);
}
